package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * Creates the first account of the device, the administrator (first launch, UC-A01).
 *
 * The session is not opened here: the caller first shows the recovery code to the user.
 */
class CreateAdministrator(
    private val users: UserRepository,
    private val hasher: SecretHasher,
    private val recoveryCodes: RecoveryCodeGenerator,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the creation. */
    sealed interface Result {
        /** The account exists. [recoveryCode] must be shown to the user now: it is not stored in clear. */
        data class Created(val user: User, val recoveryCode: RecoveryCode) : Result

        /** The name is empty. */
        data object NameBlank : Result

        /** The name is longer than [MAX_NAME_LENGTH]. */
        data object NameTooLong : Result

        /** The PIN breaks a rule. */
        data class InvalidPin(val problem: PinProblem) : Result

        /** The device already has a user: the first launch is over. */
        data object AlreadyExists : Result
    }

    /**
     * @param name name of the administrator.
     * @param pin the chosen PIN.
     * @param pinConfirmation the PIN typed a second time.
     * @return the created account with its recovery code, or why it was refused.
     */
    suspend operator fun invoke(name: String, pin: String, pinConfirmation: String): Result {
        val nameProblem = nameProblem(name)
        val choice = Pin.choose(pin, pinConfirmation)
        return when {
            users.count() > 0 -> Result.AlreadyExists
            nameProblem == UserNameProblem.BLANK -> Result.NameBlank
            nameProblem == UserNameProblem.TOO_LONG -> Result.NameTooLong
            choice is PinChoice.Refused -> Result.InvalidPin(choice.problem)
            choice is PinChoice.Accepted -> create(name.trim(), choice.pin)
            else -> error("Unreachable: every PinChoice is handled above")
        }
    }

    private suspend fun create(name: String, pin: Pin): Result.Created {
        val recoveryCode = recoveryCodes.generate()
        val credentials = Credentials(
            pinHash = hasher.hash(pin.digits),
            recoveryHash = hasher.hash(recoveryCode.value),
        )
        val user = transactions.run {
            users.create(NewUser(name, setOf(Role.ADMINISTRATOR), credentials)).also {
                auditLog.record(AuditEvent.AdministratorCreated(it.id))
            }
        }
        return Result.Created(user, recoveryCode)
    }

    /** Limits of the rule. */
    companion object {
        /** Longest user name accepted (internal rule). */
        const val MAX_NAME_LENGTH = 50

        /**
         * Checks a name alone, so a screen can refuse it before the PIN is chosen.
         *
         * @param name the name as typed; the spaces around it do not count.
         * @return the rule the name breaks, or null if it is acceptable.
         */
        fun nameProblem(name: String): UserNameProblem? {
            val trimmed = name.trim()
            return when {
                trimmed.isEmpty() -> UserNameProblem.BLANK
                trimmed.length > MAX_NAME_LENGTH -> UserNameProblem.TOO_LONG
                else -> null
            }
        }
    }
}

/** A rule broken by the name of a user. */
enum class UserNameProblem {
    /** The name is empty. */
    BLANK,

    /** The name is longer than [CreateAdministrator.MAX_NAME_LENGTH]. */
    TOO_LONG,
}
