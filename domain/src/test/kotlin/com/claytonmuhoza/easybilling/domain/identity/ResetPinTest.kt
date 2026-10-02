package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResetPinTest {

    private val fixture = IdentityFixture()
    private val newPin = "987654"

    @Test
    fun `the recovery code replaces the PIN`() = runTest {
        val created = fixture.administrator()

        val result = fixture.resetPin(created.user.id, created.recoveryCode.value, newPin, newPin)

        assertIs<ResetPin.Result.Reset>(result)
        assertEquals(LogIn.Result.Success, fixture.logIn(created.user.id, newPin))
    }

    @Test
    fun `the old PIN no longer works after a reset`() = runTest {
        val created = fixture.administrator()

        fixture.resetPin(created.user.id, created.recoveryCode.value, newPin, newPin)

        assertIs<LogIn.Result.WrongPin>(fixture.logIn(created.user.id, PIN))
    }

    @Test
    fun `the recovery code is accepted as the user typed it`() = runTest {
        val created = fixture.administrator()

        val result = fixture.resetPin(created.user.id, " reco-very-0001 ", newPin, newPin)

        assertIs<ResetPin.Result.Reset>(result)
    }

    @Test
    fun `a reset issues a new recovery code and retires the old one`() = runTest {
        val created = fixture.administrator()

        val reset = assertIs<ResetPin.Result.Reset>(
            fixture.resetPin(created.user.id, created.recoveryCode.value, newPin, newPin),
        )

        assertEquals(RecoveryCode("RECOVERY0002"), reset.newRecoveryCode)
        assertIs<ResetPin.Result.WrongCode>(
            fixture.resetPin(created.user.id, created.recoveryCode.value, PIN, PIN),
        )
        assertIs<ResetPin.Result.Reset>(
            fixture.resetPin(created.user.id, reset.newRecoveryCode.value, PIN, PIN),
        )
    }

    @Test
    fun `a reset is written to the audit log`() = runTest {
        val created = fixture.administrator()

        fixture.resetPin(created.user.id, created.recoveryCode.value, newPin, newPin)

        assertEquals(AuditEvent.PinReset(created.user.id), fixture.audit.events.last())
    }

    @Test
    fun `a wrong recovery code is rejected and counted`() = runTest {
        val user = fixture.administrator().user

        val result = fixture.resetPin(user.id, "WRONGCODE000", newPin, newPin)

        assertEquals(ResetPin.Result.WrongCode(remainingAttempts = 9), result)
        assertEquals(AuditEvent.RecoveryCodeRejected(user.id), fixture.audit.events.last())
    }

    @Test
    fun `wrong recovery codes and wrong PINs share the same lock`() = runTest {
        val created = fixture.administrator()
        repeat(9) { fixture.logIn(created.user.id, IdentityFixture.WRONG_PIN) }

        val result = fixture.resetPin(created.user.id, "WRONGCODE000", newPin, newPin)

        assertEquals(ResetPin.Result.Locked(fixture.time.now() + Duration.ofMinutes(1)), result)
        assertIs<ResetPin.Result.Locked>(
            fixture.resetPin(created.user.id, created.recoveryCode.value, newPin, newPin),
        )
    }

    @Test
    fun `an invalid new PIN is refused without spending an attempt`() = runTest {
        val created = fixture.administrator()

        val result = fixture.resetPin(created.user.id, "WRONGCODE000", "123", "123")

        assertEquals(ResetPin.Result.InvalidPin(PinProblem.TOO_SHORT), result)
        assertEquals(LoginGuard(), fixture.users.find(created.user.id)?.loginGuard)
    }

    @Test
    fun `an unknown user cannot reset a PIN`() = runTest {
        assertEquals(ResetPin.Result.UnknownUser, fixture.resetPin(UserId(42), "RECOVERY0001", newPin, newPin))
    }
}
