package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.Instant

/**
 * Lets a user who forgot the PIN choose a new one with the recovery code. The code works once:
 * a new one is issued with the new PIN.
 */
class ResetPin(
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val hasher: SecretHasher,
    private val recoveryCodes: RecoveryCodeGenerator,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a PIN reset. */
    sealed interface Result {
        /** The PIN is replaced. [newRecoveryCode] must be shown to the user now. */
        data class Reset(val newRecoveryCode: RecoveryCode) : Result

        /** The recovery code is wrong; [remainingAttempts] are left before the account locks. */
        data class WrongCode(val remainingAttempts: Int) : Result

        /** The account refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /** The new PIN breaks a rule. No attempt was spent. */
        data class InvalidPin(val problem: PinProblem) : Result

        /** The account has no recovery code: an administrator resets its PIN. No attempt was spent. */
        data object NoRecoveryCode : Result

        /** There is no user with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param userId the user who forgot the PIN.
     * @param recoveryCode the recovery code as typed, with or without dashes.
     * @param newPin the new PIN.
     * @param newPinConfirmation the new PIN typed a second time.
     * @return the new recovery code, or why the reset was refused.
     */
    suspend operator fun invoke(
        userId: UserId,
        recoveryCode: String,
        newPin: String,
        newPinConfirmation: String,
    ): Result {
        val user = users.find(userId)
        val recoveryHash = user?.credentials?.recoveryHash
        // The new PIN is checked first, so a typing mistake in it never spends an attempt.
        val choice = Pin.choose(newPin, newPinConfirmation)
        return when {
            user == null -> Result.UnknownUser
            recoveryHash == null -> Result.NoRecoveryCode
            choice is PinChoice.Refused -> Result.InvalidPin(choice.problem)
            else -> resetWithCode(user, recoveryHash, recoveryCode, (choice as PinChoice.Accepted).pin)
        }
    }

    private suspend fun resetWithCode(user: User, expected: SecretHash, recoveryCode: String, pin: Pin): Result {
        val check = guard.check(
            user = user,
            secret = RecoveryCode.normalize(recoveryCode),
            expected = expected,
            failureEvent = AuditEvent.RecoveryCodeRejected(user.id),
        )
        return when (check) {
            is SecretCheck.Accepted -> Result.Reset(replaceCredentials(check.user, pin))
            is SecretCheck.Rejected -> Result.WrongCode(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
        }
    }

    private suspend fun replaceCredentials(user: User, pin: Pin): RecoveryCode {
        val newCode = recoveryCodes.generate()
        val credentials = Credentials(
            pinHash = hasher.hash(pin.digits),
            recoveryHash = hasher.hash(newCode.value),
        )
        transactions.run {
            users.update(user.copy(credentials = credentials, pinTemporary = false))
            auditLog.record(AuditEvent.PinReset(user.id))
        }
        return newCode
    }
}
