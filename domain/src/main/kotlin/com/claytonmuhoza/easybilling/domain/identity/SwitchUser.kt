package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import java.time.Instant

/**
 * Hands the session over to another user who types their PIN, without closing the register
 * (UC-H03). The sale being typed and the sales on hold stay: they belong to the device.
 */
class SwitchUser(
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val sessions: SessionManager,
) {
    /** Outcome of a switch. */
    sealed interface Result {
        /** The other user holds the session now. */
        data object Switched : Result

        /** The PIN is wrong; [remainingAttempts] are left before the account locks. */
        data class WrongPin(val remainingAttempts: Int) : Result

        /** The account refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /**
         * The PIN of the account is temporary: the first login goes through the login screen. No
         * PIN was looked at.
         */
        data object PinChangeRequired : Result

        /** The account is disabled. No PIN was looked at. */
        data object Disabled : Result

        /** There is no user with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param userId the user who takes the session over.
     * @param pin their PIN as typed.
     * @return whether the session changed hands, or why not. The session is unchanged otherwise.
     */
    suspend operator fun invoke(userId: UserId, pin: String): Result {
        val user = users.find(userId)
        return when {
            user == null -> Result.UnknownUser
            !user.active -> Result.Disabled
            user.pinTemporary -> Result.PinChangeRequired
            else -> checkPin(user, pin)
        }
    }

    private suspend fun checkPin(user: User, pin: String): Result =
        when (val check = guard.check(user, pin, user.credentials.pinHash, AuditEvent.PinRejected(user.id))) {
            is SecretCheck.Accepted -> {
                sessions.switchTo(check.user)
                Result.Switched
            }
            is SecretCheck.Rejected -> Result.WrongPin(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
        }
}
