package com.claytonmuhoza.easybilling.customers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.customer.ArchiveCustomer
import com.claytonmuhoza.easybilling.domain.customer.CreateCustomer
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerForm
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerProblem
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerSaveResult
import com.claytonmuhoza.easybilling.domain.customer.TinCheck
import com.claytonmuhoza.easybilling.domain.customer.TinCheckFailure
import com.claytonmuhoza.easybilling.domain.customer.UpdateCustomer
import com.claytonmuhoza.easybilling.domain.customer.VerifyTin
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.navigation.CustomerEditorDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The actions on a customer, grouped so the view model receives them as one.
 *
 * @property create creates a customer.
 * @property update changes a customer.
 * @property archive archives a customer or brings it back.
 * @property verifyTin checks a TIN with the OBR.
 */
class CustomerActionsUseCases @Inject constructor(
    val create: CreateCustomer,
    val update: UpdateCustomer,
    val archive: ArchiveCustomer,
    val verifyTin: VerifyTin,
)

/** What the last check of the typed TIN gave. */
sealed interface TinNotice {
    /** The OBR knows the TIN; [name] is its owner. */
    data class Recognised(val name: String) : TinNotice

    /** The OBR does not know the TIN. */
    data object Unknown : TinNotice

    /**
     * The TIN could not be checked.
     *
     * @property failure why.
     * @property message the message of the OBR, when it gave one.
     */
    data class NotChecked(val failure: TinCheckFailure, val message: String) : TinNotice
}

/** State of the screen creating or changing a customer. */
sealed interface CustomerEditorUiState {
    /** The customer is being read. */
    data object Loading : CustomerEditorUiState

    /** The customer could not be read. */
    data object Failed : CustomerEditorUiState

    /** There is no customer with that identifier. */
    data object NotFound : CustomerEditorUiState

    /** The customer [id] is stored: the screen can close. */
    data class Saved(val id: Long) : CustomerEditorUiState

    /**
     * The form is shown.
     *
     * @property existing the customer being changed, or null for a new customer.
     * @property form the fields as typed.
     * @property problems the rules broken at the last attempt.
     * @property tinNotice what the last check of the TIN gave, or null.
     * @property busy true while a change is written or a TIN is checked.
     * @property checkingTin true while the OBR is asked about the TIN.
     * @property failed true when the last change could not be written.
     */
    data class Editing(
        val existing: Customer?,
        val form: CustomerForm,
        val problems: Set<CustomerProblem> = emptySet(),
        val tinNotice: TinNotice? = null,
        val busy: Boolean = false,
        val checkingTin: Boolean = false,
        val failed: Boolean = false,
    ) : CustomerEditorUiState {
        /** Whether the form is the one of the counter customer, whose name alone changes. */
        val counter: Boolean get() = existing?.counter == true

        /** Whether saving the form asks the OBR about its TIN: a local customer with a new TIN. */
        val savingChecksTin: Boolean
            get() = !counter && form.origin == CustomerOrigin.LOCAL && form.tin.isNotBlank() &&
                form.tin.trim() != existing?.details?.tin?.number
    }
}

/** Drives the creation, the change and the archiving of a customer (UC-B05, UC-B06). */
@HiltViewModel
class CustomerEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val customers: CustomerRepository,
    private val useCases: CustomerActionsUseCases,
    private val sessions: SessionManager,
) : ViewModel() {

    private val id: Long = savedStateHandle.toRoute<CustomerEditorDestination>().id

    private val mutableState = MutableStateFlow<CustomerEditorUiState>(CustomerEditorUiState.Loading)

    /** The state to show. */
    val state: StateFlow<CustomerEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the customer, or prepares an empty form for a new one. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { read() }.getOrElse { CustomerEditorUiState.Failed }
        }
    }

    /**
     * @param change the change of one field; the problems are cleared until the next attempt, and
     * what was said of the TIN is forgotten when the TIN changes.
     */
    fun onFormChange(change: CustomerForm.() -> CustomerForm) {
        val editing = mutableState.value as? CustomerEditorUiState.Editing ?: return
        if (editing.busy) return
        val form = editing.form.change()
        val notice = editing.tinNotice.takeIf { form.tin == editing.form.tin && form.origin == editing.form.origin }
        mutableState.value = editing.copy(form = form, problems = emptySet(), tinNotice = notice)
    }

    /** Asks the OBR about the typed TIN, and takes the name of its owner when no name is typed. */
    fun checkTin() {
        val editing = mutableState.value as? CustomerEditorUiState.Editing ?: return
        if (editing.form.tin.isBlank()) {
            mutableState.value = editing.copy(problems = setOf(CustomerProblem.TIN_MISSING))
            return
        }
        act(checkingTin = true) { current, _ ->
            when (val check = useCases.verifyTin(current.form.tin.trim())) {
                is TinCheck.Known -> current.copy(
                    form = current.form.copy(name = current.form.name.ifBlank { check.name }),
                    tinNotice = TinNotice.Recognised(check.name),
                )
                TinCheck.Unknown -> current.copy(tinNotice = TinNotice.Unknown)
                is TinCheck.Failed -> current.copy(tinNotice = TinNotice.NotChecked(check.failure, check.message))
            }
        }
    }

    /** Creates the new customer or stores the change, or shows what is wrong. */
    fun save() {
        val editing = mutableState.value as? CustomerEditorUiState.Editing ?: return
        act(checkingTin = editing.savingChecksTin) { current, actor ->
            val result = if (current.existing == null) {
                useCases.create(current.form, actor)
            } else {
                useCases.update(current.existing.id, current.form, actor)
            }
            when (result) {
                is CustomerSaveResult.Saved -> CustomerEditorUiState.Saved(result.customer.id.value)
                is CustomerSaveResult.Invalid -> current.copy(problems = result.problems)
                CustomerSaveResult.TinUnknown -> current.copy(tinNotice = TinNotice.Unknown)
                is CustomerSaveResult.NotVerified ->
                    current.copy(tinNotice = TinNotice.NotChecked(result.failure, result.message))
                CustomerSaveResult.NotFound -> CustomerEditorUiState.NotFound
            }
        }
    }

    /** Archives the customer, or brings it back. */
    fun toggleArchived() = act(checkingTin = false) { current, actor ->
        val customer = checkNotNull(current.existing)
        useCases.archive(customer.id, !customer.archived, actor)
        read()
    }

    /** Runs [block] once at a time; it receives the form as it was, no longer busy. */
    private fun act(
        checkingTin: Boolean,
        block: suspend (CustomerEditorUiState.Editing, UserId) -> CustomerEditorUiState,
    ) {
        val editing = mutableState.value as? CustomerEditorUiState.Editing ?: return
        val actor = sessions.current.value?.userId
        if (editing.busy || actor == null) return
        val idle = editing.copy(busy = false, checkingTin = false, failed = false)
        mutableState.value = idle.copy(busy = true, checkingTin = checkingTin)
        viewModelScope.launch {
            mutableState.value = storageCall { block(idle, actor) }.getOrElse { idle.copy(failed = true) }
        }
    }

    private suspend fun read(): CustomerEditorUiState {
        if (id == CustomerEditorDestination.NEW_CUSTOMER) {
            return CustomerEditorUiState.Editing(existing = null, form = CustomerForm())
        }
        return customers.find(CustomerId(id))
            ?.let { CustomerEditorUiState.Editing(existing = it, form = CustomerForm.from(it)) }
            ?: CustomerEditorUiState.NotFound
    }
}
