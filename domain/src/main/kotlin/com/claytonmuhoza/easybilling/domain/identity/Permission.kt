package com.claytonmuhoza.easybilling.domain.identity

/** Something a user may be allowed to do. A screen entry and its use cases share one permission. */
enum class Permission {
    /** Sell, issue an invoice, print it and share it. */
    SELL,

    /** See the issued invoices. */
    VIEW_INVOICES,

    /** Send a refused invoice again and decide what happens to an uncertain one. */
    RESOLVE_INVOICES,

    /** Cancel an invoice, replace it or credit it (UC-E01 to E03). */
    CORRECT_INVOICES,

    /** See the items. */
    VIEW_ITEMS,

    /** Create, change and archive items. */
    EDIT_ITEMS,

    /** See the stock of the items and their movements. */
    VIEW_STOCK,

    /** Record an entry or an exit of stock (UC-F01, F02, F05, F07). */
    RECORD_STOCK,

    /**
     * Validate an exit of stock that is not a sale, and decide what happens to a movement the OBR
     * may or may not have (UC-F07, D53).
     */
    MANAGE_STOCK,

    /** See, create, change and archive customers. */
    MANAGE_CUSTOMERS,

    /** See the state of the transmission and the exchanges with the OBR. */
    VIEW_TRANSMISSION,

    /** Change the tax profile, the EBMS connection, the taxes and the numbering. */
    CONFIGURE_BILLING,

    /** Change the printer and the header of the invoices. */
    CONFIGURE_PRINTING,

    /** Create users, change their roles, disable them and reset their PIN. */
    MANAGE_USERS,

    /** Approve a sensitive action of another user with one's own PIN. */
    AUTHORIZE_SENSITIVE_ACTION,
}

/** Decides what a set of roles may do. */
interface PermissionPolicy {
    /**
     * @param roles the roles of a user.
     * @param permission what the user wants to do.
     * @return true if at least one of [roles] gives [permission].
     */
    fun allows(roles: Set<Role>, permission: Permission): Boolean
}

/** The rights matrix of the application. It is fixed in the code: there is no free role (UC-H01). */
object FixedPermissionPolicy : PermissionPolicy {

    private val cashier = setOf(
        Permission.SELL,
        Permission.VIEW_INVOICES,
        Permission.VIEW_ITEMS,
        Permission.VIEW_STOCK,
        Permission.MANAGE_CUSTOMERS,
        Permission.VIEW_TRANSMISSION,
    )

    private val matrix: Map<Role, Set<Permission>> = mapOf(
        Role.ADMINISTRATOR to Permission.entries.toSet(),
        Role.MANAGER to Permission.entries.toSet() - setOf(Permission.CONFIGURE_BILLING, Permission.MANAGE_USERS),
        Role.CASHIER to cashier,
        Role.STOREKEEPER to setOf(
            Permission.VIEW_ITEMS,
            Permission.EDIT_ITEMS,
            Permission.VIEW_STOCK,
            Permission.RECORD_STOCK,
        ),
    )

    override fun allows(roles: Set<Role>, permission: Permission): Boolean =
        roles.any { permission in matrix.getValue(it) }
}
