package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.identity.Permission.AUTHORIZE_SENSITIVE_ACTION
import com.claytonmuhoza.easybilling.domain.identity.Permission.CONFIGURE_BILLING
import com.claytonmuhoza.easybilling.domain.identity.Permission.CONFIGURE_PRINTING
import com.claytonmuhoza.easybilling.domain.identity.Permission.EDIT_ITEMS
import com.claytonmuhoza.easybilling.domain.identity.Permission.MANAGE_CUSTOMERS
import com.claytonmuhoza.easybilling.domain.identity.Permission.MANAGE_USERS
import com.claytonmuhoza.easybilling.domain.identity.Permission.RESOLVE_INVOICES
import com.claytonmuhoza.easybilling.domain.identity.Permission.SELL
import com.claytonmuhoza.easybilling.domain.identity.Permission.VIEW_INVOICES
import com.claytonmuhoza.easybilling.domain.identity.Permission.VIEW_ITEMS
import com.claytonmuhoza.easybilling.domain.identity.Permission.VIEW_TRANSMISSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionPolicyTest {

    private val policy: PermissionPolicy = FixedPermissionPolicy

    private fun granted(vararg roles: Role): Set<Permission> =
        Permission.entries.filter { policy.allows(roles.toSet(), it) }.toSet()

    @Test
    fun `the administrator may do everything`() {
        assertEquals(Permission.entries.toSet(), granted(Role.ADMINISTRATOR))
    }

    @Test
    fun `the manager may do everything but configure the billing and manage the users`() {
        assertEquals(
            Permission.entries.toSet() - setOf(CONFIGURE_BILLING, MANAGE_USERS),
            granted(Role.MANAGER),
        )
    }

    @Test
    fun `the cashier sells, sees the invoices, the items and the transmission, and manages the customers`() {
        assertEquals(
            setOf(SELL, VIEW_INVOICES, VIEW_ITEMS, MANAGE_CUSTOMERS, VIEW_TRANSMISSION),
            granted(Role.CASHIER),
        )
    }

    @Test
    fun `the storekeeper only sees and edits the items`() {
        assertEquals(setOf(VIEW_ITEMS, EDIT_ITEMS), granted(Role.STOREKEEPER))
    }

    @Test
    fun `several roles give the union of their permissions`() {
        assertEquals(
            granted(Role.CASHIER) + granted(Role.STOREKEEPER),
            granted(Role.CASHIER, Role.STOREKEEPER),
        )
    }

    @Test
    fun `a cashier who is also a storekeeper still cannot do what neither role allows`() {
        val roles = setOf(Role.CASHIER, Role.STOREKEEPER)

        for (permission in listOf(RESOLVE_INVOICES, CONFIGURE_PRINTING, AUTHORIZE_SENSITIVE_ACTION)) {
            assertFalse(policy.allows(roles, permission), "$permission")
        }
    }

    @Test
    fun `no role gives no permission`() {
        assertEquals(emptySet(), granted())
    }

    @Test
    fun `a session answers with the roles of its user`() {
        val session = Session(UserId(1), "Bob", setOf(Role.CASHIER))

        assertTrue(session.can(SELL))
        assertFalse(session.can(EDIT_ITEMS))
    }
}
