package com.claytonmuhoza.easybilling.domain.identity

import java.time.Duration
import java.time.Instant

/** Decides when an account is locked after wrong secrets. */
interface LockoutPolicy {
    /** Number of consecutive wrong secrets that locks the account. */
    val maxAttempts: Int

    /**
     * Computes the protection state after one more wrong secret.
     *
     * @param guard the state before the failure.
     * @param now the instant of the failure.
     * @return the state after the failure, locked or not.
     */
    fun afterFailure(guard: LoginGuard, now: Instant): LoginGuard
}

/**
 * Locks the account for a fixed duration once [maxAttempts] is reached, then starts counting
 * again from zero. Internal rule: 10 attempts, 1 minute.
 *
 * @param maxAttempts number of consecutive wrong secrets that locks the account.
 * @param lockDuration how long the account stays locked.
 */
class FixedLockoutPolicy(
    override val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val lockDuration: Duration = DEFAULT_LOCK_DURATION,
) : LockoutPolicy {

    override fun afterFailure(guard: LoginGuard, now: Instant): LoginGuard {
        val attempts = guard.failedAttempts + 1
        return if (attempts >= maxAttempts) {
            LoginGuard(failedAttempts = 0, lockedUntil = now + lockDuration)
        } else {
            LoginGuard(failedAttempts = attempts)
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        val DEFAULT_LOCK_DURATION: Duration = Duration.ofMinutes(1)
    }
}
