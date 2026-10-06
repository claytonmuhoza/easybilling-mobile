package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.TEMPORARY_PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.WRONG_PIN
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeTemporaryPinTest {

    private val fixture = IdentityFixture()

    private suspend fun bob(): User =
        fixture.user(UserForm("Bob", setOf(Role.CASHIER)), fixture.administrator().user.id)

    @Test
    fun `a temporary PIN does not open a session`() = runTest {
        val bob = bob()

        assertEquals(LogIn.Result.PinChangeRequired, fixture.logIn(bob.id, TEMPORARY_PIN))
        assertNull(fixture.sessions.current.value)
    }

    @Test
    fun `a wrong temporary PIN is a wrong PIN`() = runTest {
        val bob = bob()

        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 9), fixture.logIn(bob.id, WRONG_PIN))
    }

    @Test
    fun `choosing a PIN with the temporary one opens the session`() = runTest {
        val bob = bob()

        val result = fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, PIN, PIN)

        assertEquals(ChangeTemporaryPin.Result.Changed, result)
        assertFalse(fixture.users.find(bob.id)!!.pinTemporary)
        assertEquals(Session(bob.id, "Bob", setOf(Role.CASHIER)), fixture.sessions.current.value)
        assertTrue(AuditEvent.TemporaryPinChanged(bob.id) in fixture.audit.events)
        assertEquals(AuditEvent.SessionOpened(bob.id), fixture.audit.events.last())
    }

    @Test
    fun `the chosen PIN then logs in`() = runTest {
        val bob = bob()
        fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, PIN, PIN)
        fixture.sessions.close(LogoutReason.MANUAL)

        assertEquals(LogIn.Result.Success, fixture.logIn(bob.id, PIN))
        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 9), fixture.logIn(bob.id, TEMPORARY_PIN))
    }

    @Test
    fun `the chosen PIN cannot be the temporary one`() = runTest {
        val bob = bob()

        assertEquals(
            ChangeTemporaryPin.Result.SamePin,
            fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, TEMPORARY_PIN, TEMPORARY_PIN),
        )
        assertTrue(fixture.users.find(bob.id)!!.pinTemporary)
    }

    @Test
    fun `an invalid new PIN spends no attempt`() = runTest {
        val bob = bob()

        assertEquals(
            ChangeTemporaryPin.Result.InvalidPin(PinProblem.MISMATCH),
            fixture.changeTemporaryPin(bob.id, WRONG_PIN, PIN, "999999"),
        )
        assertEquals(LoginGuard(), fixture.users.find(bob.id)!!.loginGuard)
    }

    @Test
    fun `a wrong temporary PIN counts in the lock`() = runTest {
        val bob = bob()

        assertEquals(
            ChangeTemporaryPin.Result.WrongPin(remainingAttempts = 9),
            fixture.changeTemporaryPin(bob.id, WRONG_PIN, PIN, PIN),
        )
        assertNull(fixture.sessions.current.value)
    }

    @Test
    fun `an account whose PIN is not temporary cannot use this way`() = runTest {
        val admin = fixture.administrator().user.id

        assertEquals(ChangeTemporaryPin.Result.NotTemporary, fixture.changeTemporaryPin(admin, PIN, "777777", "777777"))
        assertEquals(ChangeTemporaryPin.Result.UnknownUser, fixture.changeTemporaryPin(UserId(99), PIN, PIN, PIN))
    }

    @Test
    fun `a disabled account does not log in`() = runTest {
        val bob = bob()
        fixture.setUserActive(bob.id, false, UserId(1))

        assertEquals(LogIn.Result.Disabled, fixture.logIn(bob.id, TEMPORARY_PIN))
        assertEquals(
            ChangeTemporaryPin.Result.Disabled,
            fixture.changeTemporaryPin(bob.id, TEMPORARY_PIN, PIN, PIN),
        )
        assertEquals(LoginGuard(), fixture.users.find(bob.id)!!.loginGuard)
    }

    @Test
    fun `an account without a recovery code cannot reset its PIN alone`() = runTest {
        val bob = bob()

        assertEquals(ResetPin.Result.NoRecoveryCode, fixture.resetPin(bob.id, "RECOVERY0001", PIN, PIN))
        assertEquals(LoginGuard(), fixture.users.find(bob.id)!!.loginGuard)
    }
}
