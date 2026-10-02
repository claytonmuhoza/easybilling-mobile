package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.PIN
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture.Companion.WRONG_PIN
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LogInTest {

    private val fixture = IdentityFixture()

    private suspend fun failTimes(userId: UserId, times: Int): LogIn.Result {
        var last: LogIn.Result = LogIn.Result.UnknownUser
        repeat(times) { last = fixture.logIn(userId, WRONG_PIN) }
        return last
    }

    @Test
    fun `the right PIN opens a session`() = runTest {
        val user = fixture.administrator().user

        val result = fixture.logIn(user.id, PIN)

        assertEquals(LogIn.Result.Success, result)
        assertEquals(Session(user.id, "Alice", Role.ADMINISTRATOR), fixture.sessions.current.value)
        assertEquals(AuditEvent.SessionOpened(user.id), fixture.audit.events.last())
    }

    @Test
    fun `a wrong PIN is rejected with the attempts left`() = runTest {
        val user = fixture.administrator().user

        val result = fixture.logIn(user.id, WRONG_PIN)

        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 9), result)
        assertNull(fixture.sessions.current.value)
        assertEquals(AuditEvent.PinRejected(user.id), fixture.audit.events.last())
    }

    @Test
    fun `nine wrong PINs leave one attempt`() = runTest {
        val user = fixture.administrator().user

        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 1), failTimes(user.id, 9))
    }

    @Test
    fun `the tenth wrong PIN locks the account for one minute`() = runTest {
        val user = fixture.administrator().user
        val lockEnd = fixture.time.now() + Duration.ofMinutes(1)

        val result = failTimes(user.id, 10)

        assertEquals(LogIn.Result.Locked(until = lockEnd), result)
        assertEquals(AuditEvent.AccountLocked(user.id, lockEnd), fixture.audit.events.last())
    }

    @Test
    fun `the right PIN is refused while the account is locked`() = runTest {
        val user = fixture.administrator().user
        failTimes(user.id, 10)
        fixture.time.advance(Duration.ofSeconds(59))

        val result = fixture.logIn(user.id, PIN)

        assertIs<LogIn.Result.Locked>(result)
        assertNull(fixture.sessions.current.value)
    }

    @Test
    fun `attempts made while locked are neither counted nor audited`() = runTest {
        val user = fixture.administrator().user
        failTimes(user.id, 10)
        val eventsBefore = fixture.audit.events.size

        failTimes(user.id, 3)

        assertEquals(eventsBefore, fixture.audit.events.size)
        assertEquals(0, fixture.users.find(user.id)?.loginGuard?.failedAttempts)
    }

    @Test
    fun `the right PIN works once the minute has passed`() = runTest {
        val user = fixture.administrator().user
        failTimes(user.id, 10)
        fixture.time.advance(Duration.ofMinutes(1))

        assertEquals(LogIn.Result.Success, fixture.logIn(user.id, PIN))
        assertEquals(LoginGuard(), fixture.users.find(user.id)?.loginGuard)
    }

    @Test
    fun `ten new attempts are allowed after a lock`() = runTest {
        val user = fixture.administrator().user
        failTimes(user.id, 10)
        fixture.time.advance(Duration.ofMinutes(1))

        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 9), fixture.logIn(user.id, WRONG_PIN))
    }

    @Test
    fun `a successful login restarts the count of failures`() = runTest {
        val user = fixture.administrator().user
        failTimes(user.id, 9)

        fixture.logIn(user.id, PIN)

        assertEquals(LogIn.Result.WrongPin(remainingAttempts = 9), fixture.logIn(user.id, WRONG_PIN))
    }

    @Test
    fun `an unknown user cannot log in`() = runTest {
        assertEquals(LogIn.Result.UnknownUser, fixture.logIn(UserId(42), PIN))
    }
}
