package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.testing.IdentityFixture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccessGuardTest {

    private val fixture = IdentityFixture()

    @Test
    fun `a user who has the permission goes on`() = runTest {
        val admin = fixture.administrator().user.id
        val events = fixture.audit.events.size

        fixture.access.require(admin, Permission.MANAGE_USERS)

        assertEquals(events, fixture.audit.events.size)
    }

    @Test
    fun `a user without the permission is stopped and the refusal is traced`() = runTest {
        val admin = fixture.administrator().user.id
        val bob = fixture.user(UserForm("Bob", setOf(Role.CASHIER)), admin)

        val refusal = assertFailsWith<PermissionDeniedException> {
            fixture.access.require(bob.id, Permission.EDIT_ITEMS)
        }

        assertEquals(Permission.EDIT_ITEMS, refusal.permission)
        assertEquals(AuditEvent.PermissionDenied(bob.id, Permission.EDIT_ITEMS), fixture.audit.events.last())
    }

    @Test
    fun `the roles are read from the stored account, not from an old session`() = runTest {
        val admin = fixture.administrator().user.id
        val bob = fixture.user(UserForm("Bob", setOf(Role.STOREKEEPER)), admin)
        fixture.access.require(bob.id, Permission.EDIT_ITEMS)

        fixture.updateUser(bob.id, UserForm("Bob", setOf(Role.CASHIER)), admin)

        assertFailsWith<PermissionDeniedException> { fixture.access.require(bob.id, Permission.EDIT_ITEMS) }
    }

    @Test
    fun `a disabled account may do nothing`() = runTest {
        val admin = fixture.administrator().user.id
        val bob = fixture.user(UserForm("Bob", setOf(Role.CASHIER)), admin)
        fixture.setUserActive(bob.id, false, admin)

        assertFailsWith<PermissionDeniedException> { fixture.access.require(bob.id, Permission.SELL) }
    }

    @Test
    fun `an unknown account may do nothing`() = runTest {
        assertFailsWith<PermissionDeniedException> { fixture.access.require(UserId(99), Permission.SELL) }
    }
}
