package com.claytonmuhoza.easybilling.data.local.identity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.LoginGuard
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/**
 * Row of the `users` table. Secrets are stored derived (salt, hash, iterations), never in clear.
 * [roles] holds the names of the roles separated by commas; the three recovery columns are null
 * together for an account without a recovery code.
 */
@Entity(tableName = "users")
internal class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    val roles: String,
    val pinSalt: ByteArray,
    val pinHash: ByteArray,
    val pinIterations: Int,
    val recoverySalt: ByteArray?,
    val recoveryHash: ByteArray?,
    val recoveryIterations: Int?,
    val failedAttempts: Int,
    val lockedUntilMillis: Long?,
    val active: Boolean,
    val pinTemporary: Boolean,
)

internal fun UserEntity.toDomain() = User(
    id = UserId(id),
    name = name,
    roles = roles.split(ROLE_SEPARATOR).map(Role::valueOf).toSet(),
    credentials = Credentials(
        pinHash = SecretHash(pinSalt, pinHash, pinIterations),
        recoveryHash = recoveryHash(),
    ),
    loginGuard = LoginGuard(
        failedAttempts = failedAttempts,
        lockedUntil = lockedUntilMillis?.let(Instant::ofEpochMilli),
    ),
    active = active,
    pinTemporary = pinTemporary,
)

private fun UserEntity.recoveryHash(): SecretHash? =
    if (recoverySalt != null && recoveryHash != null && recoveryIterations != null) {
        SecretHash(recoverySalt, recoveryHash, recoveryIterations)
    } else {
        null
    }

internal fun User.toEntity() = entity(id.value, NewUser(name, roles, credentials, pinTemporary), loginGuard, active)

internal fun NewUser.toEntity() = entity(0, this, LoginGuard(), active = true)

private fun entity(id: Long, user: NewUser, guard: LoginGuard, active: Boolean) = UserEntity(
    id = id,
    name = user.name,
    roles = user.roles.sorted().joinToString(ROLE_SEPARATOR) { it.name },
    pinSalt = user.credentials.pinHash.salt,
    pinHash = user.credentials.pinHash.hash,
    pinIterations = user.credentials.pinHash.iterations,
    recoverySalt = user.credentials.recoveryHash?.salt,
    recoveryHash = user.credentials.recoveryHash?.hash,
    recoveryIterations = user.credentials.recoveryHash?.iterations,
    failedAttempts = guard.failedAttempts,
    lockedUntilMillis = guard.lockedUntil?.toEpochMilli(),
    active = active,
    pinTemporary = user.pinTemporary,
)

private const val ROLE_SEPARATOR = ","
