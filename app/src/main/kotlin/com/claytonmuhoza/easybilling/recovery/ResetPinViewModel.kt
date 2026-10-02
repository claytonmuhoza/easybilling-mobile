package com.claytonmuhoza.easybilling.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.ResetPin
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
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

/** State of the new PIN screen. */
sealed interface ResetPinUiState {
    /**
     * The form is shown.
     *
     * @property recoveryCode the recovery code typed so far.
     * @property pin the new PIN typed so far.
     * @property confirmation the new PIN confirmation typed so far.
     * @property codeError why the submitted recovery code was refused, or null.
     * @property pinError problem with the submitted PIN, or null.
     * @property submitting true while the code is being checked.
     */
    data class Form(
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

/** Drives the choice of a new PIN with the recovery code. */
@HiltViewModel
class ResetPinViewModel @Inject constructor(
    private val users: UserRepository,
    private val resetPin: ResetPin,
) : ViewModel() {

    private val mutableState = MutableStateFlow<ResetPinUiState>(ResetPinUiState.Form())

    /** The state to show. */
    val state: StateFlow<ResetPinUiState> = mutableState.asStateFlow()

    /** @param code the new content of the recovery code field. */
    fun onRecoveryCodeChange(code: String) = updateForm { copy(recoveryCode = code, codeError = null) }

    /** @param pin the new content of the PIN field. */
    fun onPinChange(pin: String) = updateForm { copy(pin = pin, pinError = null) }

    /** @param confirmation the new content of the PIN confirmation field. */
    fun onConfirmationChange(confirmation: String) = updateForm { copy(confirmation = confirmation, pinError = null) }

    /** Replaces the PIN if the recovery code is right, or shows what is wrong. */
    fun submit() {
        val form = mutableState.value as? ResetPinUiState.Form ?: return
        if (form.submitting) return
        mutableState.value = form.copy(submitting = true)
        viewModelScope.launch {
            val user = users.findAll().firstOrNull()
            val result = user?.let { resetPin(it.id, form.recoveryCode, form.pin, form.confirmation) }
            mutableState.value = when (result) {
                is ResetPin.Result.Reset -> ResetPinUiState.Done(result.newRecoveryCode.formatted)
                is ResetPin.Result.WrongCode ->
                    form.copy(codeError = RecoveryCodeError.Wrong(result.remainingAttempts))
                is ResetPin.Result.Locked -> form.copy(codeError = RecoveryCodeError.Locked)
                is ResetPin.Result.InvalidPin -> form.copy(pinError = result.problem)
                ResetPin.Result.UnknownUser, null -> form
            }
        }
    }

    private fun updateForm(change: ResetPinUiState.Form.() -> ResetPinUiState.Form) {
        val form = mutableState.value as? ResetPinUiState.Form ?: return
        mutableState.value = form.change()
    }
}
