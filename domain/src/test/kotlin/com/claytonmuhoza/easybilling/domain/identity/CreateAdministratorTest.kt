package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateAdministratorTest {

    private val fixture = IdentityFixture()

    @Test
    fun `creates the administrator and returns a recovery code`() = runTest {
        val created = fixture.administrator()

        assertEquals("Alice", created.user.name)
        assertEquals(Role.ADMINISTRATOR, created.user.role)
        assertEquals(RecoveryCode("RECOVERY0001"), created.recoveryCode)
        assertEquals(listOf(created.user), fixture.users.findAll())
    }

    @Test
    fun `the name is trimmed`() = runTest {
        val result = fixture.createAdministrator("  Alice  ", IdentityFixture.PIN, IdentityFixture.PIN)

        assertEquals("Alice", (result as CreateAdministrator.Result.Created).user.name)
    }

    @Test
    fun `the creation is written to the audit log`() = runTest {
        val created = fixture.administrator()

        assertEquals(listOf<AuditEvent>(AuditEvent.AdministratorCreated(created.user.id)), fixture.audit.events)
    }

    @Test
    fun `creating the account does not open a session`() = runTest {
        fixture.administrator()

        assertNull(fixture.sessions.current.value)
    }

    @Test
    fun `a blank name is refused`() = runTest {
        val result = fixture.createAdministrator("   ", IdentityFixture.PIN, IdentityFixture.PIN)

        assertEquals(CreateAdministrator.Result.NameBlank, result)
        assertEquals(0, fixture.users.count())
    }

    @Test
    fun `a name longer than fifty characters is refused`() = runTest {
        val result = fixture.createAdministrator("a".repeat(51), IdentityFixture.PIN, IdentityFixture.PIN)

        assertEquals(CreateAdministrator.Result.NameTooLong, result)
    }

    @Test
    fun `an invalid PIN is refused with its problem`() = runTest {
        assertEquals(
            CreateAdministrator.Result.InvalidPin(PinProblem.TOO_SHORT),
            fixture.createAdministrator("Alice", "12345", "12345"),
        )
        assertEquals(
            CreateAdministrator.Result.InvalidPin(PinProblem.MISMATCH),
            fixture.createAdministrator("Alice", "123456", "123450"),
        )
        assertEquals(0, fixture.users.count())
    }

    @Test
    fun `a second administrator cannot be created by the first launch assistant`() = runTest {
        fixture.administrator()

        val result = fixture.createAdministrator("Bob", IdentityFixture.PIN, IdentityFixture.PIN)

        assertEquals(CreateAdministrator.Result.AlreadyExists, result)
        assertEquals(1, fixture.users.count())
    }
}
