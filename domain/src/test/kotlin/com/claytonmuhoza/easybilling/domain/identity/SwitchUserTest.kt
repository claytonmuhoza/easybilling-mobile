package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.TEMPORARY_PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.WRONG_PIN
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The quick switch to another user without closing the register (UC-H03). */
class SwitchUserTest {

    private val fixture = IdentityFixture()

    /** Alice is logged in; Bob, a cashier, has chosen his own PIN [PIN]. */
    private suspend fun aliceThenBob(): Pair<User, User> {
        val alice = fixture.administrator().user
        val bob = fixture.user(UserForm("Bob", setOf(Role.CASHIER)), alice.id)
        fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, PIN, PIN)
        fixture.sessions.close(LogoutReason.MANUAL)
        fixture.logIn(alice.id, PIN)
        return alice to bob
    }

    @Test
    fun `the right PIN hands the session to the other user, and both ends are audited`() = runTest {
        val (alice, bob) = aliceThenBob()
        val before = fixture.audit.events.size

        assertEquals(SwitchUser.Result.Switched, fixture.switchUser(bob.id, PIN))

        assertEquals(Session(bob.id, "Bob", setOf(Role.CASHIER)), fixture.sessions.current.value)
        assertEquals(
            listOf(AuditEvent.SessionClosed(alice.id, LogoutReason.SWITCH), AuditEvent.SessionOpened(bob.id)),
            fixture.audit.events.drop(before),
        )
    }

    @Test
    fun `a wrong PIN keeps the session and counts towards the lock of the other user`() = runTest {
        val (alice, bob) = aliceThenBob()

        assertEquals(SwitchUser.Result.WrongPin(remainingAttempts = 9), fixture.switchUser(bob.id, WRONG_PIN))
        assertEquals(alice.id, fixture.sessions.current.value?.userId)

        repeat(8) { fixture.switchUser(bob.id, WRONG_PIN) }
        assertIs<SwitchUser.Result.Locked>(fixture.switchUser(bob.id, WRONG_PIN))
        assertIs<SwitchUser.Result.Locked>(fixture.switchUser(bob.id, PIN))
        assertEquals(alice.id, fixture.sessions.current.value?.userId)
    }

    @Test
    fun `an account with a temporary PIN goes through the login, and no attempt is spent`() = runTest {
        val alice = fixture.administrator().user
        val carine = fixture.user(UserForm("Carine", setOf(Role.CASHIER)), alice.id)
        fixture.logIn(alice.id, PIN)

        assertEquals(SwitchUser.Result.PinChangeRequired, fixture.switchUser(carine.id, WRONG_PIN))
        assertEquals(0, fixture.users.find(carine.id)?.loginGuard?.failedAttempts)
        assertEquals(alice.id, fixture.sessions.current.value?.userId)
    }

    @Test
    fun `a disabled or unknown account is refused`() = runTest {
        val (alice, bob) = aliceThenBob()
        fixture.setUserActive(bob.id, active = false, actor = alice.id)

        assertEquals(SwitchUser.Result.Disabled, fixture.switchUser(bob.id, PIN))
        assertEquals(SwitchUser.Result.UnknownUser, fixture.switchUser(UserId(99), PIN))
        assertEquals(alice.id, fixture.sessions.current.value?.userId)
    }

    @Test
    fun `switching with nobody logged in simply opens the session`() = runTest {
        val (_, bob) = aliceThenBob()
        fixture.sessions.close(LogoutReason.MANUAL)
        val before = fixture.audit.events.size

        assertEquals(SwitchUser.Result.Switched, fixture.switchUser(bob.id, PIN))

        assertEquals(listOf<AuditEvent>(AuditEvent.SessionOpened(bob.id)), fixture.audit.events.drop(before))
    }
}
