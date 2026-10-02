package com.claytonmuhoza.easybilling.domain.identity

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedLockoutPolicyTest {

    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val policy = FixedLockoutPolicy()

    @Test
    fun `the default rule is ten attempts`() {
        assertEquals(10, policy.maxAttempts)
    }

    @Test
    fun `the ninth failure does not lock`() {
        val guard = policy.afterFailure(LoginGuard(failedAttempts = 8), now)

        assertEquals(LoginGuard(failedAttempts = 9, lockedUntil = null), guard)
    }

    @Test
    fun `the tenth failure locks for one minute and restarts the count`() {
        val guard = policy.afterFailure(LoginGuard(failedAttempts = 9), now)

        assertEquals(LoginGuard(failedAttempts = 0, lockedUntil = now + Duration.ofMinutes(1)), guard)
    }

    @Test
    fun `a guard is locked until the end of the lock, not after`() {
        val guard = LoginGuard(lockedUntil = now + Duration.ofMinutes(1))

        assertTrue(guard.isLocked(now))
        assertTrue(guard.isLocked(now + Duration.ofSeconds(59)))
        assertFalse(guard.isLocked(now + Duration.ofMinutes(1)))
    }
}
