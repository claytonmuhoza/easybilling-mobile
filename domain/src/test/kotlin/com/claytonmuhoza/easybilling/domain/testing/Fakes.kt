package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
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
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
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

/** Tax profile versions kept in a list. */
class InMemoryTaxProfileRepository : TaxProfileRepository {
    private val versions = mutableListOf<TaxProfileVersion>()

    override suspend fun current(): TaxProfileVersion? = versions.lastOrNull()

    override suspend fun history(): List<TaxProfileVersion> = versions.reversed()

    override suspend fun find(number: Int): TaxProfileVersion? = versions.firstOrNull { it.number == number }

    override suspend fun append(version: TaxProfileVersion) {
        require(versions.none { it.number == version.number }) { "Version ${version.number} already exists" }
        versions += version
    }
}

/** EBMS settings kept in a field. */
class InMemoryEbmsSettingsRepository(var settings: EbmsSettings = EbmsSettings()) : EbmsSettingsRepository {
    override suspend fun read(): EbmsSettings = settings

    override suspend fun write(settings: EbmsSettings) {
        this.settings = settings
    }
}

/** EBMS credentials kept in a map. */
class InMemoryCredentialsVault : EbmsCredentialsVault {
    val credentials = mutableMapOf<EbmsEnvironment, EbmsCredentials>()

    override suspend fun read(environment: EbmsEnvironment): EbmsCredentials? = credentials[environment]

    override suspend fun write(environment: EbmsEnvironment, credentials: EbmsCredentials) {
        this.credentials[environment] = credentials
    }
}

/** OBR login that answers what the test chose, and remembers each call. */
class FakeObrAuthentication(var outcome: LoginOutcome = LoginOutcome.Success("token-1")) : ObrAuthentication {
    val calls = mutableListOf<LoginTarget>()

    override suspend fun login(target: LoginTarget): LoginOutcome {
        calls += target
        return outcome
    }
}

/** Exchanges kept in a list. */
class InMemoryExchangeLog : ExchangeLog {
    val exchanges = mutableListOf<ObrExchange>()

    override suspend fun record(exchange: ObrExchange): Long {
        val id = exchanges.size + 1L
        exchanges += exchange.copy(id = id)
        return id
    }

    override suspend fun recent(limit: Int): List<ObrExchange> = exchanges.reversed().take(limit)

    override suspend fun find(id: Long): ObrExchange? = exchanges.firstOrNull { it.id == id }

    override suspend fun deleteBefore(instant: Instant): Int {
        val before = exchanges.size
        exchanges.removeAll { it.startedAt < instant }
        return before - exchanges.size
    }
}

/** Tax definitions kept in a map. */
class InMemoryTaxRepository : TaxRepository {
    private val taxes = LinkedHashMap<TaxId, TaxDefinition>()

    override suspend fun all(): List<TaxDefinition> = taxes.values.toList()

    override suspend fun find(id: TaxId): TaxDefinition? = taxes[id]

    override suspend fun create(kind: TaxKind, name: String, version: TaxVersion): TaxDefinition {
        val tax = TaxDefinition(TaxId(taxes.size + 1L), kind, name, listOf(version))
        taxes[tax.id] = tax
        return tax
    }

    override suspend fun addVersion(id: TaxId, version: TaxVersion) {
        val tax = taxes.getValue(id)
        taxes[id] = tax.copy(versions = tax.versions + version)
    }

    override suspend fun setArchived(id: TaxId, archived: Boolean) {
        taxes[id] = taxes.getValue(id).copy(archived = archived)
    }
}

/** Runs the block directly: there is nothing to roll back in memory. */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
