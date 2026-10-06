package com.claytonmuhoza.easybilling.login

import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinChoice
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.identity.isConfirmationProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** How the choice of a PIN that replaces a temporary one ended without opening a session. */
sealed interface TemporaryPinExit {
    /** The user went back to the PIN of the account. */
    data object Back : TemporaryPinExit

    /** The temporary PIN no longer matches; [remainingAttempts] are left before the account locks. */
    data class WrongPin(val remainingAttempts: Int) : TemporaryPinExit

    /** The account refuses every attempt until [until]. */
    data class Locked(val until: Instant) : TemporaryPinExit

    /** The account was disabled, removed or no longer has a temporary PIN. */
    data object AccountChanged : TemporaryPinExit
}

/**
 * The choice of the user's own PIN after a login with a temporary one, step by step. The
 * temporary PIN stays in memory only, for the time of the choice.
 *
 * @param changeTemporaryPin the use case that replaces the PIN and opens the session.
 * @param scope where the replacement runs.
 * @param onExit called when the choice ends without opening a session.
 */
class TemporaryPinChange(
    private val changeTemporaryPin: ChangeTemporaryPin,
    private val scope: CoroutineScope,
    private val onExit: (TemporaryPinExit) -> Unit,
) {
    private val mutableState = MutableStateFlow<LoginUiState.NewPin?>(null)
    private var user: User? = null
    private var temporaryPin: String? = null

    /** The choice shown, or null when no temporary PIN is being replaced. */
    val state: StateFlow<LoginUiState.NewPin?> = mutableState.asStateFlow()

    /**
     * Starts the choice.
     *
     * @param user the user who logged in with a temporary PIN.
     * @param temporaryPin the temporary PIN the user typed.
     */
    fun start(user: User, temporaryPin: String) {
        this.user = user
        this.temporaryPin = temporaryPin
        mutableState.value = LoginUiState.NewPin(userName = user.name)
    }

    /** @param pin the new PIN after a key of the pad was pressed. A new PIN needs a new confirmation. */
    fun onPinChange(pin: String) =
        update { copy(pin = pin, confirmation = "", pinError = null, sameAsTemporary = false) }

    /** @param confirmation the new PIN confirmation after a key of the pad was pressed. */
    fun onConfirmationChange(confirmation: String) = update { copy(confirmation = confirmation, pinError = null) }

    /** Goes to the confirmation of the new PIN; the confirmation replaces the temporary PIN. */
    fun next() {
        val form = mutableState.value?.takeUnless { it.submitting } ?: return
        when (form.step) {
            // The PIN is checked against itself: only the confirmation step can find a mismatch.
            NewPinStep.PIN -> mutableState.value = when (val choice = Pin.choose(form.pin, form.pin)) {
                is PinChoice.Refused -> form.copy(pinError = choice.problem)
                is PinChoice.Accepted -> if (form.pin == temporaryPin) {
                    form.copy(sameAsTemporary = true)
                } else {
                    form.copy(step = NewPinStep.CONFIRMATION)
                }
            }
            NewPinStep.CONFIRMATION -> replace(form)
        }
    }

    /** Returns to the previous step, or leaves the choice from the first one. */
    fun back() {
        val form = mutableState.value ?: return
        if (form.step == NewPinStep.CONFIRMATION) {
            mutableState.value = form.copy(step = NewPinStep.PIN, pinError = null)
        } else {
            exit(TemporaryPinExit.Back)
        }
    }

    private fun replace(form: LoginUiState.NewPin) {
        val user = user ?: return
        val temporary = temporaryPin ?: return
        mutableState.value = form.copy(submitting = true)
        scope.launch {
            val idle = form.copy(submitting = false)
            when (val result = changeTemporaryPin(user.id, temporary, form.pin, form.confirmation)) {
                // The session is open: the login is left by itself.
                ChangeTemporaryPin.Result.Changed -> exit(exit = null)
                is ChangeTemporaryPin.Result.InvalidPin -> mutableState.value = idle.refused(result.problem)
                ChangeTemporaryPin.Result.SamePin ->
                    mutableState.value = idle.copy(step = NewPinStep.PIN, sameAsTemporary = true)
                // The temporary PIN was right a moment ago: the account changed meanwhile.
                is ChangeTemporaryPin.Result.WrongPin -> exit(TemporaryPinExit.WrongPin(result.remainingAttempts))
                is ChangeTemporaryPin.Result.Locked -> exit(TemporaryPinExit.Locked(result.until))
                ChangeTemporaryPin.Result.NotTemporary,
                ChangeTemporaryPin.Result.Disabled,
                ChangeTemporaryPin.Result.UnknownUser,
                -> exit(TemporaryPinExit.AccountChanged)
            }
        }
    }

    /** Forgets the temporary PIN; [exit] is told to the login, null when the session opened. */
    private fun exit(exit: TemporaryPinExit?) {
        user = null
        temporaryPin = null
        mutableState.value = null
        exit?.let(onExit)
    }

    private fun update(change: LoginUiState.NewPin.() -> LoginUiState.NewPin) {
        mutableState.value = mutableState.value?.change()
    }
}

/** A mismatch is typed again at the confirmation; any other problem is fixed at the PIN step. */
private fun LoginUiState.NewPin.refused(problem: PinProblem): LoginUiState.NewPin =
    if (isConfirmationProblem(problem)) {
        copy(confirmation = "", pinError = problem)
    } else {
        copy(step = NewPinStep.PIN, pinError = problem)
    }
