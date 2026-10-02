package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCode
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.Duration
import java.time.Instant

/** Clock moved by hand. */
class MutableTimeProvider(var instant: Instant = Instant.parse("2026-10-02T08:00:00Z")) : TimeProvider {
    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}

/** Users kept in a map. */
class InMemoryUserRepository : UserRepository {
    private val users = LinkedHashMap<UserId, User>()

    override suspend fun count(): Int = users.size

    override suspend fun findAll(): List<User> = users.values.toList()

    override suspend fun find(id: UserId): User? = users[id]

    override suspend fun create(newUser: NewUser): User {
        val user = User(
            id = UserId(users.size + 1L),
            name = newUser.name,
            role = newUser.role,
            credentials = newUser.credentials,
        )
        users[user.id] = user
        return user
    }

    override suspend fun update(user: User) {
        users[user.id] = user
    }
}

/** Reversible "hash", fast and deterministic: the tests of the real one are in `:platform`. */
class FakeSecretHasher : SecretHasher {
    override suspend fun hash(secret: String): SecretHash =
        SecretHash(salt = byteArrayOf(1), hash = secret.reversed().toByteArray(), iterations = 1)

    override suspend fun verify(secret: String, expected: SecretHash): Boolean =
        expected.hash.contentEquals(secret.reversed().toByteArray())
}

/** Predictable recovery codes: `RECOVERY0001`, `RECOVERY0002`... */
class SequentialRecoveryCodes : RecoveryCodeGenerator {
    private var count = 0

    override fun generate(): RecoveryCode = RecoveryCode("RECOVERY%04d".format(++count))
}

/** Audit log that only remembers the events it was given. */
class RecordingAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()

    override suspend fun record(event: AuditEvent) {
        events += event
    }

    override suspend fun verify(): AuditVerification = AuditVerification.Intact
}

/** Audit entries kept in a list that tests may tamper with. */
class InMemoryAuditStore : AuditStore {
    val entries = mutableListOf<AuditEntry>()

    override suspend fun last(): AuditEntry? = entries.lastOrNull()

    override suspend fun append(entry: AuditEntry) {
        entries += entry
    }

    override suspend fun readAll(): List<AuditEntry> = entries.toList()
}

/** Runs the block directly: there is nothing to roll back in memory. */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
