package com.claytonmuhoza.easybilling.recovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinChoice
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.ResetPin
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.identity.isConfirmationProblem
import com.claytonmuhoza.easybilling.navigation.ResetPinDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a recovery code was refused. */
sealed interface RecoveryCodeError {
    /** The code is wrong; [remainingAttempts] are left before the account locks. */
    data class Wrong(val remainingAttempts: Int) : RecoveryCodeError

    /** The account is locked: no code is looked at for now. */
    data object Locked : RecoveryCodeError
}

/** The steps of the new PIN screen, in order. */
enum class ResetPinStep {
    /** The recovery code, typed with the keyboard. */
    CODE,

    /** The new PIN, typed on the PIN pad. */
    PIN,

    /** The new PIN a second time. */
    CONFIRMATION,
}

/** State of the new PIN screen. */
sealed interface ResetPinUiState {
    /**
     * A step of the form is shown.
     *
     * @property step the step shown.
     * @property recoveryCode the recovery code typed so far.
     * @property pin the new PIN typed so far.
     * @property confirmation the new PIN confirmation typed so far.
     * @property codeError why the submitted recovery code was refused, or null.
     * @property pinError problem with the PIN submitted at the step shown, or null.
     * @property submitting true while the code is being checked.
     */
    data class Form(
        val step: ResetPinStep = ResetPinStep.CODE,
        val recoveryCode: String = "",
        val pin: String = "",
        val confirmation: String = "",
        val codeError: RecoveryCodeError? = null,
        val pinError: PinProblem? = null,
        val submitting: Boolean = false,
    ) : ResetPinUiState

    /** The PIN is replaced; the new recovery code, formatted in groups, must be shown. */
    data class Done(val newRecoveryCode: String) : ResetPinUiState
}

/** Drives the choice of a new PIN with the recovery code, step by step, for the user who forgot it. */
@HiltViewModel
class ResetPinViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resetPin: ResetPin,
) : ViewModel() {

    private val userId = UserId(savedStateHandle.toRoute<ResetPinDestination>().userId)

    private val mutableState = MutableStateFlow<ResetPinUiState>(ResetPinUiState.Form())

    /** The state to show. */
    val state: StateFlow<ResetPinUiState> = mutableState.asStateFlow()

    /** @param code the new content of the recovery code field. */
    fun onRecoveryCodeChange(code: String) = updateForm { copy(recoveryCode = code, codeError = null) }

    /** @param pin the new PIN after a key of the pad was pressed. A new PIN needs a new confirmation. */
    fun onPinChange(pin: String) = updateForm { copy(pin = pin, confirmation = "", pinError = null) }

    /** @param confirmation the PIN confirmation after a key of the pad was pressed. */
    fun onConfirmationChange(confirmation: String) = updateForm { copy(confirmation = confirmation, pinError = null) }

    /**
     * Goes to the next step; the last step replaces the PIN if the recovery code is right. The
     * code is only checked then, with the new PIN: every check of it counts towards the lock.
     */
    fun next() {
        val form = mutableState.value as? ResetPinUiState.Form ?: return
        if (form.submitting) return
        when (form.step) {
            ResetPinStep.CODE -> if (form.recoveryCode.isNotBlank()) {
                mutableState.value = form.copy(step = ResetPinStep.PIN)
            }
            // The PIN is checked against itself: only the confirmation step can find a mismatch.
            ResetPinStep.PIN -> mutableState.value = when (val choice = Pin.choose(form.pin, form.pin)) {
                is PinChoice.Accepted -> form.copy(step = ResetPinStep.CONFIRMATION)
                is PinChoice.Refused -> form.copy(pinError = choice.problem)
            }
            ResetPinStep.CONFIRMATION -> submit(form)
        }
    }

    /**
     * Returns to the previous step, keeping what was typed.
     *
     * @return false on the first step, which has no previous step: the screen is then left.
     */
    fun back(): Boolean {
        val form = mutableState.value as? ResetPinUiState.Form
        val previous = when (form?.step) {
            ResetPinStep.PIN -> ResetPinStep.CODE
            ResetPinStep.CONFIRMATION -> ResetPinStep.PIN
            ResetPinStep.CODE, null -> null
        }
        if (form != null && previous != null) {
            mutableState.value = form.copy(step = previous, pinError = null)
        }
        return previous != null
    }

    /** Replaces the PIN, or shows what is wrong at the step where it is typed. */
    private fun submit(form: ResetPinUiState.Form) {
        mutableState.value = form.copy(submitting = true)
        viewModelScope.launch {
            val result = resetPin(userId, form.recoveryCode, form.pin, form.confirmation)
            mutableState.value = when (result) {
                is ResetPin.Result.Reset -> ResetPinUiState.Done(result.newRecoveryCode.formatted)
                // The new PIN is kept: only the code has to be typed again.
                is ResetPin.Result.WrongCode -> form.copy(
                    step = ResetPinStep.CODE,
                    codeError = RecoveryCodeError.Wrong(result.remainingAttempts),
                )
                is ResetPin.Result.Locked -> form.copy(step = ResetPinStep.CODE, codeError = RecoveryCodeError.Locked)
                is ResetPin.Result.InvalidPin -> form.refused(result.problem)
                // The login offers no recovery code to an account without one.
                ResetPin.Result.NoRecoveryCode, ResetPin.Result.UnknownUser -> form
            }
        }
    }

    /** A mismatch is typed again at the confirmation; any other problem is fixed at the PIN step. */
    private fun ResetPinUiState.Form.refused(problem: PinProblem): ResetPinUiState.Form =
        if (isConfirmationProblem(problem)) {
            copy(confirmation = "", pinError = problem)
        } else {
            copy(step = ResetPinStep.PIN, pinError = problem)
        }

    private fun updateForm(change: ResetPinUiState.Form.() -> ResetPinUiState.Form) {
        val form = mutableState.value as? ResetPinUiState.Form ?: return
        mutableState.value = form.change()
    }
}
