package com.claytonmuhoza.easybilling.domain.identity

import java.time.Instant

/** Identifier of a user on this device. */
@JvmInline
value class UserId(val value: Long)

/** What a user is allowed to do. The other roles arrive with user management. */
enum class Role {
    /** Puts the application into service and manages its settings and users. */
    ADMINISTRATOR,
}

/**
 * The stored secrets of a user.
 *
 * @property pinHash the derived PIN.
 * @property recoveryHash the derived recovery code.
 */
class Credentials(val pinHash: SecretHash, val recoveryHash: SecretHash)

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
 * @property name name shown on screen and, later, printed on receipts.
 * @property role what the user is allowed to do.
 * @property credentials the stored secrets.
 * @property loginGuard state of the protection against PIN guessing.
 */
data class User(
    val id: UserId,
    val name: String,
    val role: Role,
    val credentials: Credentials,
    val loginGuard: LoginGuard = LoginGuard(),
)

/**
 * A user about to be stored, before it has an identifier.
 *
 * @property name name of the user.
 * @property role role of the user.
 * @property credentials the stored secrets.
 */
data class NewUser(val name: String, val role: Role, val credentials: Credentials)

/** Storage of the users. */
interface UserRepository {
    /** @return the number of users. */
    suspend fun count(): Int

    /** @return every user, oldest first. */
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
