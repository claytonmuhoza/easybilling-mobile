package com.claytonmuhoza.easybilling.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinChoice
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserNameProblem
import com.claytonmuhoza.easybilling.identity.isConfirmationProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The steps of the first launch assistant, in order. */
enum class SetupStep {
    /** The name of the administrator, typed with the keyboard. */
    NAME,

    /** The PIN, typed on the PIN pad. */
    PIN,

    /** The PIN a second time. */
    CONFIRMATION,
}

/** State of the first launch assistant. */
sealed interface SetupUiState {
    /**
     * A step of the form is shown.
     *
     * @property step the step shown.
     * @property name the name typed so far.
     * @property pin the PIN typed so far.
     * @property confirmation the PIN confirmation typed so far.
     * @property nameError problem with the submitted name, or null.
     * @property pinError problem with the PIN submitted at the step shown, or null.
     * @property submitting true while the account is being created.
     */
    data class Form(
        val step: SetupStep = SetupStep.NAME,
        val name: String = "",
        val pin: String = "",
        val confirmation: String = "",
        val nameError: UserNameProblem? = null,
        val pinError: PinProblem? = null,
        val submitting: Boolean = false,
    ) : SetupUiState

    /** The account exists; its recovery code, formatted in groups, must be shown. */
    data class Created(val recoveryCode: String) : SetupUiState

    /** The device already had an account: there is nothing to set up. */
    data object AlreadySetUp : SetupUiState
}

/** Drives the first launch assistant (UC-A01): creation of the administrator account, step by step. */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val createAdministrator: CreateAdministrator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SetupUiState>(SetupUiState.Form())
    private var createdUser: User? = null

    /** The state to show. */
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    /** @param name the new content of the name field. */
    fun onNameChange(name: String) = updateForm { copy(name = name, nameError = null) }

    /** @param pin the PIN after a key of the pad was pressed. A new PIN needs a new confirmation. */
    fun onPinChange(pin: String) = updateForm { copy(pin = pin, confirmation = "", pinError = null) }

    /** @param confirmation the PIN confirmation after a key of the pad was pressed. */
    fun onConfirmationChange(confirmation: String) = updateForm { copy(confirmation = confirmation, pinError = null) }

    /** Checks the step shown, then goes to the next one; the last step creates the account. */
    fun next() {
        val form = mutableState.value as? SetupUiState.Form ?: return
        if (form.submitting) return
        when (form.step) {
            SetupStep.NAME -> mutableState.value = when (val problem = CreateAdministrator.nameProblem(form.name)) {
                null -> form.copy(step = SetupStep.PIN)
                else -> form.copy(nameError = problem)
            }
            // The PIN is checked against itself: only the confirmation step can find a mismatch.
            SetupStep.PIN -> mutableState.value = when (val choice = Pin.choose(form.pin, form.pin)) {
                is PinChoice.Accepted -> form.copy(step = SetupStep.CONFIRMATION)
                is PinChoice.Refused -> form.copy(pinError = choice.problem)
            }
            SetupStep.CONFIRMATION -> create(form)
        }
    }

    /** Returns to the previous step, keeping what was typed. Does nothing on the first step. */
    fun back() = updateForm {
        when (step) {
            SetupStep.NAME -> this
            SetupStep.PIN -> copy(step = SetupStep.NAME, pinError = null)
            SetupStep.CONFIRMATION -> copy(step = SetupStep.PIN, pinError = null)
        }
    }

    /**
     * Opens the session of the new administrator once the recovery code was written down.
     *
     * @param onDone called when the session is open.
     */
    fun acknowledgeRecoveryCode(onDone: () -> Unit) {
        val user = createdUser ?: return
        viewModelScope.launch {
            sessions.open(user)
            onDone()
        }
    }

    /** Creates the account, or shows what is wrong at the step where it is typed. */
    private fun create(form: SetupUiState.Form) {
        mutableState.value = form.copy(submitting = true)
        viewModelScope.launch {
            mutableState.value = when (val result = createAdministrator(form.name, form.pin, form.confirmation)) {
                is CreateAdministrator.Result.Created -> {
                    createdUser = result.user
                    SetupUiState.Created(result.recoveryCode.formatted)
                }
                CreateAdministrator.Result.NameBlank ->
                    form.copy(step = SetupStep.NAME, nameError = UserNameProblem.BLANK)
                CreateAdministrator.Result.NameTooLong ->
                    form.copy(step = SetupStep.NAME, nameError = UserNameProblem.TOO_LONG)
                is CreateAdministrator.Result.InvalidPin -> form.refused(result.problem)
                CreateAdministrator.Result.AlreadyExists -> SetupUiState.AlreadySetUp
            }
        }
    }

    /** A mismatch is typed again at the confirmation; any other problem is fixed at the PIN step. */
    private fun SetupUiState.Form.refused(problem: PinProblem): SetupUiState.Form =
        if (isConfirmationProblem(problem)) {
            copy(confirmation = "", pinError = problem)
        } else {
            copy(step = SetupStep.PIN, pinError = problem)
        }

    private fun updateForm(change: SetupUiState.Form.() -> SetupUiState.Form) {
        val form = mutableState.value as? SetupUiState.Form ?: return
        mutableState.value = form.change()
    }
}
