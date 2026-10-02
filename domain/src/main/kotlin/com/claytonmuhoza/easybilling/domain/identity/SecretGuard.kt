package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.Instant

/** Outcome of [SecretGuard.check]. */
sealed interface SecretCheck {
    /** The secret is the right one. [user] has its failure count cleared. */
    data class Accepted(val user: User) : SecretCheck

    /** The secret is wrong; [remainingAttempts] are left before the account locks. */
    data class Rejected(val remainingAttempts: Int) : SecretCheck

    /** The account refuses every attempt until [until]. The secret was not looked at. */
    data class Locked(val until: Instant) : SecretCheck
}

/**
 * Checks a secret typed by a user while enforcing the [LockoutPolicy]. The PIN and the recovery
 * code go through the same guard, so they share one failure count and one lock.
 */
class SecretGuard(
    private val users: UserRepository,
    private val hasher: SecretHasher,
    private val policy: LockoutPolicy,
    private val time: TimeProvider,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /**
     * Checks [secret] against [expected] for [user].
     *
     * A failure is counted, stored and audited. Nothing is counted while the account is locked.
     *
     * @param user the user the secret belongs to.
     * @param secret the secret as typed.
     * @param expected the stored hash of the right secret.
     * @param failureEvent the audit event to record if the secret is wrong.
     * @return whether the secret was accepted, rejected, or not looked at because of a lock.
     */
    suspend fun check(
        user: User,
        secret: String,
        expected: SecretHash,
        failureEvent: AuditEvent,
    ): SecretCheck {
        val now = time.now()
        val lockedUntil = user.loginGuard.lockedUntil
        return when {
            lockedUntil != null && now < lockedUntil -> SecretCheck.Locked(lockedUntil)
            hasher.verify(secret, expected) -> SecretCheck.Accepted(clearGuard(user))
            else -> reject(user, now, failureEvent)
        }
    }

    private suspend fun reject(user: User, now: Instant, failureEvent: AuditEvent): SecretCheck {
        // An expired lock leaves no trace: the user gets a full set of attempts again.
        val before = if (user.loginGuard.lockedUntil != null) LoginGuard() else user.loginGuard
        val after = policy.afterFailure(before, now)
        transactions.run {
            users.update(user.copy(loginGuard = after))
            auditLog.record(failureEvent)
            after.lockedUntil?.let { auditLog.record(AuditEvent.AccountLocked(user.id, it)) }
        }
        return after.lockedUntil?.let { SecretCheck.Locked(it) }
            ?: SecretCheck.Rejected(policy.maxAttempts - after.failedAttempts)
    }

    private suspend fun clearGuard(user: User): User {
        val cleared = user.copy(loginGuard = LoginGuard())
        if (cleared != user) {
            users.update(cleared)
        }
        return cleared
    }
}
