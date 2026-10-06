package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.TEMPORARY_PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.WRONG_PIN
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A sensitive action approved with the PIN of a manager or an administrator (UC-H04). */
class AuthorizeSensitiveActionTest {

    private val fixture = IdentityFixture()
    private val action = SensitiveAction.LineDiscount(itemCode = "ART-0001", rate = BigDecimal("25"))

    /** Creates [name] with [roles], who then chooses the PIN [PIN]. */
    private suspend fun account(name: String, vararg roles: Role, by: UserId): User {
        val user = fixture.user(UserForm(name, roles.toSet()), by)
        fixture.changeTemporaryPin(user.id, TEMPORARY_PIN, PIN, PIN)
        return user
    }

    /** Alice (administrator), Marc (manager) and Bob (cashier, logged in). */
    private suspend fun shop(): Triple<User, User, User> {
        val alice = fixture.administrator().user
        val marc = account("Marc", Role.MANAGER, by = alice.id)
        val bob = account("Bob", Role.CASHIER, by = alice.id)
        return Triple(alice, marc, bob)
    }

    @Test
    fun `the PIN of a manager approves the action without changing the session`() = runTest {
        val (_, marc, bob) = shop()

        val result = fixture.authorize(bob.id, marc.id, PIN, action)

        assertEquals(AuthorizeSensitiveAction.Result.Authorized(marc.id), result)
        assertEquals(bob.id, fixture.sessions.current.value?.userId)
        assertEquals(AuditEvent.SensitiveActionAuthorized(marc.id, bob.id, action), fixture.audit.events.last())
    }

    @Test
    fun `a wrong PIN is refused, audited, and counts towards the lock of the approver`() = runTest {
        val (_, marc, bob) = shop()

        val result = fixture.authorize(bob.id, marc.id, WRONG_PIN, action)

        assertEquals(AuthorizeSensitiveAction.Result.WrongPin(remainingAttempts = 9), result)
        assertEquals(1, fixture.users.find(marc.id)?.loginGuard?.failedAttempts)
        assertTrue(AuditEvent.PinRejected(marc.id) in fixture.audit.events)
        assertEquals(AuditEvent.SensitiveActionRefused(bob.id, marc.id, action), fixture.audit.events.last())

        repeat(8) { fixture.authorize(bob.id, marc.id, WRONG_PIN, action) }
        assertIs<AuthorizeSensitiveAction.Result.Locked>(fixture.authorize(bob.id, marc.id, WRONG_PIN, action))
        assertIs<AuthorizeSensitiveAction.Result.Locked>(fixture.authorize(bob.id, marc.id, PIN, action))
    }

    @Test
    fun `nobody approves their own action, nor without the right, nor with a disabled account`() = runTest {
        val (alice, marc, bob) = shop()
        val carl = account("Carl", Role.CASHIER, by = alice.id)

        assertEquals(AuthorizeSensitiveAction.Result.NotAllowed, fixture.authorize(marc.id, marc.id, PIN, action))
        assertEquals(AuthorizeSensitiveAction.Result.NotAllowed, fixture.authorize(bob.id, carl.id, PIN, action))
        fixture.setUserActive(marc.id, active = false, actor = alice.id)
        assertEquals(AuthorizeSensitiveAction.Result.NotAllowed, fixture.authorize(bob.id, marc.id, PIN, action))
        assertEquals(AuthorizeSensitiveAction.Result.NotAllowed, fixture.authorize(bob.id, UserId(99), PIN, action))
        // No PIN was looked at: nothing counts towards a lock.
        assertEquals(0, fixture.users.find(carl.id)?.loginGuard?.failedAttempts)
    }

    @Test
    fun `a manager whose PIN is still temporary cannot approve`() = runTest {
        val (alice, _, bob) = shop()
        val nadia = fixture.user(UserForm("Nadia", setOf(Role.MANAGER)), alice.id)

        assertEquals(
            AuthorizeSensitiveAction.Result.NotAllowed,
            fixture.authorize(bob.id, nadia.id, TEMPORARY_PIN, action),
        )
    }

    @Test
    fun `the approvers are the active accounts allowed to approve, the requester apart`() = runTest {
        val (alice, marc, bob) = shop()
        account("Ines", Role.CASHIER, Role.MANAGER, by = alice.id)

        val approvers = fixture.authorizeSensitiveAction.approvers(bob.id)
        assertEquals(listOf("Alice", "Marc", "Ines"), approvers.map { it.name })
        assertEquals(listOf("Alice", "Ines"), fixture.authorizeSensitiveAction.approvers(marc.id).map { it.name })
    }
}
