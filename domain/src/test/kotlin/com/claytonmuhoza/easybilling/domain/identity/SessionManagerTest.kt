package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {

    private val fixture = IdentityFixture()
    private val sessions = fixture.sessions

    private suspend fun openSession(): User {
        val user = fixture.administrator().user
        sessions.open(user)
        return user
    }

    @Test
    fun `there is no session at start`() {
        assertNull(sessions.current.value)
    }

    @Test
    fun `a manual logout closes the session and is audited`() = runTest {
        val user = openSession()

        sessions.close(LogoutReason.MANUAL)

        assertNull(sessions.current.value)
        assertEquals(AuditEvent.SessionClosed(user.id, LogoutReason.MANUAL), fixture.audit.events.last())
    }

    @Test
    fun `closing without a session does nothing`() = runTest {
        sessions.close(LogoutReason.MANUAL)

        assertTrue(fixture.audit.events.isEmpty())
    }

    @Test
    fun `the session survives just under five minutes of inactivity`() = runTest {
        openSession()
        fixture.time.advance(Duration.ofMinutes(5).minusSeconds(1))

        assertFalse(sessions.expireIfInactive())
        assertNotNull(sessions.current.value)
    }

    @Test
    fun `the session expires after five minutes of inactivity`() = runTest {
        val user = openSession()
        fixture.time.advance(Duration.ofMinutes(5))

        assertTrue(sessions.expireIfInactive())
        assertNull(sessions.current.value)
        assertEquals(AuditEvent.SessionClosed(user.id, LogoutReason.INACTIVITY), fixture.audit.events.last())
    }

    @Test
    fun `an action postpones the expiry`() = runTest {
        openSession()
        fixture.time.advance(Duration.ofMinutes(4))
        sessions.touch()
        fixture.time.advance(Duration.ofMinutes(4))

        assertFalse(sessions.expireIfInactive())
    }

    @Test
    fun `a clock set back in time expires the session`() = runTest {
        openSession()
        fixture.time.advance(Duration.ofHours(-1))

        assertTrue(sessions.expireIfInactive())
    }

    @Test
    fun `nothing expires without a session`() = runTest {
        fixture.time.advance(Duration.ofHours(1))

        assertFalse(sessions.expireIfInactive())
    }
}
