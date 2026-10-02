package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import java.time.Instant

/** Opens a session for a user who types the right PIN (UC-H02). */
class LogIn(
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val sessions: SessionManager,
) {
    /** Outcome of a login attempt. */
    sealed interface Result {
        /** The session is open. */
        data object Success : Result

        /** The PIN is wrong; [remainingAttempts] are left before the account locks. */
        data class WrongPin(val remainingAttempts: Int) : Result

        /** The account refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /** There is no user with that identifier. */
        data object UnknownUser : Result
    }

    /**
     * @param userId the user who logs in.
     * @param pin the PIN as typed.
     * @return whether the session was opened, or why not.
     */
    suspend operator fun invoke(userId: UserId, pin: String): Result {
        val user = users.find(userId) ?: return Result.UnknownUser
        val check = guard.check(user, pin, user.credentials.pinHash, AuditEvent.PinRejected(user.id))
        return when (check) {
            is SecretCheck.Accepted -> {
                sessions.open(check.user)
                Result.Success
            }
            is SecretCheck.Rejected -> Result.WrongPin(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
        }
    }
}
