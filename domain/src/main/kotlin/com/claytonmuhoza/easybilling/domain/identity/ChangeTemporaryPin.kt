package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.Instant

/**
 * Lets a user replace the temporary PIN given by an administrator with one of their own, then
 * opens the session. [LogIn] sends here with [LogIn.Result.PinChangeRequired].
 */
class ChangeTemporaryPin(
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val hasher: SecretHasher,
    private val sessions: SessionManager,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change. */
    sealed interface Result {
        /** The PIN is replaced and the session is open. */
        data object Changed : Result

        /** The temporary PIN is wrong; [remainingAttempts] are left before the account locks. */
        data class WrongPin(val remainingAttempts: Int) : Result

        /** The account refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /** The new PIN breaks a rule. No attempt was spent. */
        data class InvalidPin(val problem: PinProblem) : Result

        /** The new PIN is the temporary one. No attempt was spent. */
        data object SamePin : Result

        /** The PIN of the account is not a temporary one. */
        data object NotTemporary : Result

        /** The account is disabled. */
        data object Disabled : Result

        /** There is no account with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param userId the user who logs in.
     * @param temporaryPin the PIN given by the administrator.
     * @param newPin the PIN the user chooses.
     * @param newPinConfirmation the chosen PIN typed a second time.
     * @return whether the PIN was replaced and the session opened.
     */
    suspend operator fun invoke(
        userId: UserId,
        temporaryPin: String,
        newPin: String,
        newPinConfirmation: String,
    ): Result {
        val user = users.find(userId) ?: return Result.UnknownUser
        val choice = Pin.choose(newPin, newPinConfirmation)
        return when {
            !user.active -> Result.Disabled
            !user.pinTemporary -> Result.NotTemporary
            // The new PIN is checked first, so a typing mistake in it never spends an attempt.
            choice is PinChoice.Refused -> Result.InvalidPin(choice.problem)
            newPin == temporaryPin -> Result.SamePin
            else -> change(user, temporaryPin, (choice as PinChoice.Accepted).pin)
        }
    }

    private suspend fun change(user: User, temporaryPin: String, pin: Pin): Result {
        val check = guard.check(user, temporaryPin, user.credentials.pinHash, AuditEvent.PinRejected(user.id))
        return when (check) {
            is SecretCheck.Rejected -> Result.WrongPin(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
            is SecretCheck.Accepted -> {
                val credentials = Credentials(hasher.hash(pin.digits), user.credentials.recoveryHash)
                val changed = check.user.copy(credentials = credentials, pinTemporary = false)
                transactions.run {
                    users.update(changed)
                    auditLog.record(AuditEvent.TemporaryPinChanged(user.id))
                }
                sessions.open(changed)
                Result.Changed
            }
        }
    }
}
