package com.claytonmuhoza.easybilling.invoices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CancelInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationKind
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationReason
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationRules
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.domain.invoicing.ReplaceInvoice
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.approvalFor
import com.claytonmuhoza.easybilling.identity.refusalOf
import com.claytonmuhoza.easybilling.navigation.CancelInvoiceDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the cancellation of an invoice uses, grouped so the view model receives it as one.
 *
 * @property invoices the issued invoices.
 * @property rules tells whether an invoice may be cancelled now.
 * @property cancel cancels an invoice.
 * @property replace starts the replacement of an invoice.
 * @property authorize approves the correction with the PIN of a manager.
 */
class InvoiceCorrectionUseCases @Inject constructor(
    val invoices: InvoiceRepository,
    val rules: CancellationRules,
    val cancel: CancelInvoice,
    val replace: ReplaceInvoice,
    val authorize: AuthorizeSensitiveAction,
)

/**
 * A correction waiting for the approval of a manager.
 *
 * @property kind what was asked for.
 * @property dialog the choice of the approver and their PIN.
 */
data class PendingCorrection(val kind: CancellationKind, val dialog: ApprovalUiState)

/** State of the cancellation of an invoice. */
sealed interface CancelInvoiceUiState {
    /** The invoice is being read. */
    data object Loading : CancelInvoiceUiState

    /** The invoice could not be read. */
    data object Failed : CancelInvoiceUiState

    /** The invoice cannot be cancelled now, for [problem]. */
    data class NotCancellable(val problem: CancellationProblem) : CancelInvoiceUiState

    /**
     * The reason can be typed.
     *
     * @property number the number of the invoice.
     * @property reason the reason typed.
     * @property reasonProblem the rule the reason broke at the last attempt, or null.
     * @property refusal why the invoice could not be cancelled at the last attempt, or null.
     * @property pending the correction waiting for the approval of a manager, or null.
     * @property busy true while the correction is written.
     * @property failed true when the last attempt could not be written.
     */
    data class Form(
        val number: String,
        val reason: String = "",
        val reasonProblem: ReasonProblem? = null,
        val refusal: CancellationProblem? = null,
        val pending: PendingCorrection? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : CancelInvoiceUiState

    /** The invoice is cancelled ([CancellationKind.CANCEL]) or its replacing sale started. */
    data class Done(val kind: CancellationKind) : CancelInvoiceUiState
}

/**
 * Drives the cancellation of an invoice, alone or with its replacement (UC-E01, E02). A user who
 * may not correct an invoice asks for the approval of a manager (UC-H04).
 */
@HiltViewModel
class CancelInvoiceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val useCases: InvoiceCorrectionUseCases,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val id = InvoiceId(savedStateHandle.toRoute<CancelInvoiceDestination>().id)

    private val mutableState = MutableStateFlow<CancelInvoiceUiState>(CancelInvoiceUiState.Loading)

    /** The state to show. */
    val state: StateFlow<CancelInvoiceUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the invoice. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall {
                val problem = useCases.rules.problem(id)
                val number = useCases.invoices.find(id)?.content?.number
                when {
                    problem != null -> CancelInvoiceUiState.NotCancellable(problem)
                    number == null -> CancelInvoiceUiState.NotCancellable(CancellationProblem.NOT_FOUND)
                    else -> CancelInvoiceUiState.Form(number)
                }
            }.getOrElse { CancelInvoiceUiState.Failed }
        }
    }

    /** @param reason the new content of the reason field. */
    fun onReasonChange(reason: String) = update { copy(reason = reason, reasonProblem = null, refusal = null) }

    /**
     * Cancels the invoice, or starts its replacement; asks for the approval of a manager first when
     * the user may not correct an invoice.
     *
     * @param kind what is asked for.
     */
    fun submit(kind: CancellationKind) {
        val form = (mutableState.value as? CancelInvoiceUiState.Form)?.takeUnless { it.busy } ?: return
        val session = sessions.current.value ?: return
        val problem = CancellationReason.problem(form.reason)
        when {
            problem != null -> mutableState.value = form.copy(reasonProblem = problem)
            session.can(Permission.CORRECT_INVOICES) -> run(form, kind, approver = null)
            else -> viewModelScope.launch {
                val dialog = useCases.authorize.approvalFor(session.userId)
                mutableState.value = form.copy(pending = PendingCorrection(kind, dialog))
            }
        }
    }

    /** @param approverId the approver picked; the PIN typed so far is cleared. */
    fun choose(approverId: Long) = updateDialog { copy(chosen = approverId, pin = "", error = null) }

    /** @param pin the PIN of the approver after a key of the pad was pressed. */
    fun onPinChange(pin: String) = updateDialog { copy(pin = pin, error = null) }

    /** Gives the approval up: the reason stays typed. */
    fun dismissApproval() = update { copy(pending = null) }

    /** Checks the PIN of the approver; once approved, the correction is made with that approval. */
    fun approve() {
        val form = (mutableState.value as? CancelInvoiceUiState.Form)?.takeUnless { it.busy } ?: return
        val pending = form.pending?.takeIf { it.dialog.chosen != null } ?: return
        val approver = UserId(checkNotNull(pending.dialog.chosen))
        mutableState.value = form.copy(busy = true)
        viewModelScope.launch {
            val actor = sessions.current.value?.userId ?: return@launch
            val action = SensitiveAction.InvoiceCancellation(form.number, pending.kind == CancellationKind.REPLACE)
            val error = useCases.authorize.refusalOf(actor, approver, pending.dialog.pin, action)
            val idle = form.copy(busy = false)
            if (error == null) {
                run(idle.copy(pending = null), pending.kind, approver)
            } else {
                val dialog = pending.dialog.copy(pin = "", error = error)
                mutableState.value = idle.copy(pending = pending.copy(dialog = dialog))
            }
        }
    }

    private fun run(form: CancelInvoiceUiState.Form, kind: CancellationKind, approver: UserId?) {
        val actor = sessions.current.value?.userId ?: return
        mutableState.value = form.copy(busy = true, failed = false)
        viewModelScope.launch {
            val idle = form.copy(busy = false)
            mutableState.value = storageCall {
                when (kind) {
                    CancellationKind.CANCEL -> when (val result = useCases.cancel(id, form.reason, actor, approver)) {
                        CancelInvoice.Result.Requested -> CancelInvoiceUiState.Done(kind).also { transmission.kick() }
                        is CancelInvoice.Result.InvalidReason -> idle.copy(reasonProblem = result.problem)
                        is CancelInvoice.Result.Refused -> idle.copy(refusal = result.problem)
                    }
                    CancellationKind.REPLACE -> when (val result = useCases.replace(id, form.reason, actor, approver)) {
                        is ReplaceInvoice.Result.Started -> CancelInvoiceUiState.Done(kind)
                        is ReplaceInvoice.Result.InvalidReason -> idle.copy(reasonProblem = result.problem)
                        is ReplaceInvoice.Result.Refused -> idle.copy(refusal = result.problem)
                    }
                }
            }.getOrElse { idle.copy(failed = true) }
        }
    }

    private fun updateDialog(change: ApprovalUiState.() -> ApprovalUiState) = update {
        copy(pending = pending?.let { it.copy(dialog = it.dialog.change()) })
    }

    private fun update(change: CancelInvoiceUiState.Form.() -> CancelInvoiceUiState.Form) {
        val form = mutableState.value as? CancelInvoiceUiState.Form ?: return
        mutableState.value = form.change()
    }
}
