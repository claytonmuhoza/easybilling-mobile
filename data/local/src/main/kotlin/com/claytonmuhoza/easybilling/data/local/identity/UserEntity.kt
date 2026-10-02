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

/** Row of the `users` table. Secrets are stored derived (salt, hash, iterations), never in clear. */
@Entity(tableName = "users")
internal class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    val role: String,
    val pinSalt: ByteArray,
    val pinHash: ByteArray,
    val pinIterations: Int,
    val recoverySalt: ByteArray,
    val recoveryHash: ByteArray,
    val recoveryIterations: Int,
    val failedAttempts: Int,
    val lockedUntilMillis: Long?,
)

internal fun UserEntity.toDomain() = User(
    id = UserId(id),
    name = name,
    role = Role.valueOf(role),
    credentials = Credentials(
        pinHash = SecretHash(pinSalt, pinHash, pinIterations),
        recoveryHash = SecretHash(recoverySalt, recoveryHash, recoveryIterations),
    ),
    loginGuard = LoginGuard(
        failedAttempts = failedAttempts,
        lockedUntil = lockedUntilMillis?.let(Instant::ofEpochMilli),
    ),
)

internal fun User.toEntity() = entity(id.value, name, role, credentials, loginGuard)

/** A new user has no identifier yet: 0 lets the database generate it. */
internal fun NewUser.toEntity() = entity(0, name, role, credentials, LoginGuard())

private fun entity(id: Long, name: String, role: Role, credentials: Credentials, guard: LoginGuard) =
    UserEntity(
        id = id,
        name = name,
        role = role.name,
        pinSalt = credentials.pinHash.salt,
        pinHash = credentials.pinHash.hash,
        pinIterations = credentials.pinHash.iterations,
        recoverySalt = credentials.recoveryHash.salt,
        recoveryHash = credentials.recoveryHash.hash,
        recoveryIterations = credentials.recoveryHash.iterations,
        failedAttempts = guard.failedAttempts,
        lockedUntilMillis = guard.lockedUntil?.toEpochMilli(),
    )
