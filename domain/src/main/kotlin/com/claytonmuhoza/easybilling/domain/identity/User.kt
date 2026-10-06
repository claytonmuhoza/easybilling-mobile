package com.claytonmuhoza.easybilling.domain.identity

import java.time.Instant

/** Identifier of a user on this device. */
@JvmInline
value class UserId(val value: Long)

/**
 * A role a user may hold. A user holds one or several; what each allows is decided by the
 * [PermissionPolicy].
 */
enum class Role {
    /** Puts the application into service and manages its settings and users. */
    ADMINISTRATOR,

    /** Runs the shop: approves sensitive actions and follows the invoices. */
    MANAGER,

    /** Sells and issues the invoices. */
    CASHIER,

    /** Keeps the items and, later, the stock. */
    STOREKEEPER,
}

/**
 * The stored secrets of a user.
 *
 * @property pinHash the derived PIN.
 * @property recoveryHash the derived recovery code, or null for an account created by an
 * administrator: a forgotten PIN is then reset by an administrator.
 */
class Credentials(val pinHash: SecretHash, val recoveryHash: SecretHash?)

/**
 * Protection of an account against PIN guessing.
 *
 * @property failedAttempts wrong secrets typed since the last success or the last lock.
 * @property lockedUntil end of the current lock, or null if the account was never locked since
 * the last success.
 */
data class LoginGuard(val failedAttempts: Int = 0, val lockedUntil: Instant? = null) {
    /**
     * @param now the current instant.
     * @return true while the account refuses every attempt.
     */
    fun isLocked(now: Instant): Boolean = lockedUntil != null && now < lockedUntil
}

/**
 * A person who uses the application on this device.
 *
 * @property id identifier of the user.
 * @property name name shown on screen and printed on the documents the user issues.
 * @property roles what the user is allowed to do; never empty.
 * @property credentials the stored secrets.
 * @property loginGuard state of the protection against PIN guessing.
 * @property active false once the account is disabled: it cannot log in, but the invoices and the
 * audit log keep referring to it.
 * @property pinTemporary true while the PIN is one given by an administrator, which the user has
 * to replace before a session opens.
 */
data class User(
    val id: UserId,
    val name: String,
    val roles: Set<Role>,
    val credentials: Credentials,
    val loginGuard: LoginGuard = LoginGuard(),
    val active: Boolean = true,
    val pinTemporary: Boolean = false,
) {
    /** True if the user holds the administrator role and may log in. */
    val isActiveAdministrator: Boolean get() = active && Role.ADMINISTRATOR in roles
}

/**
 * A user about to be stored, before it has an identifier.
 *
 * @property name name of the user.
 * @property roles roles of the user; never empty.
 * @property credentials the stored secrets.
 * @property pinTemporary true if the PIN has to be replaced at the first login.
 */
data class NewUser(
    val name: String,
    val roles: Set<Role>,
    val credentials: Credentials,
    val pinTemporary: Boolean = false,
)

/** Storage of the users. */
interface UserRepository {
    /** @return the number of users, disabled ones included. */
    suspend fun count(): Int

    /** @return every user, disabled ones included, oldest first. */
    suspend fun findAll(): List<User>

    /**
     * @param id identifier of the user.
     * @return the user, or null if there is none with that identifier.
     */
    suspend fun find(id: UserId): User?

    /**
     * Stores a new user.
     *
     * @param newUser the user to store.
     * @return the stored user, with its identifier.
     */
    suspend fun create(newUser: NewUser): User

    /**
     * Replaces a stored user.
     *
     * @param user the new state of the user identified by its [User.id].
     */
    suspend fun update(user: User)
}
