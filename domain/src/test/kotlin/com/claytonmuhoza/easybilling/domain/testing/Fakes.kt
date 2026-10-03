package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.catalog.SearchText
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerSearchText
import com.claytonmuhoza.easybilling.domain.customer.TinLookup
import com.claytonmuhoza.easybilling.domain.customer.TinReply
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCode
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider
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

/** Numbering and counters kept in memory. */
class InMemoryNumberingRepository : NumberingRepository {
    private var settings: NumberingSettings? = null
    private val counters = mutableMapOf<Pair<DocumentType, Int>, Long>()

    override suspend fun settings(): NumberingSettings? = settings

    override suspend fun save(settings: NumberingSettings) {
        this.settings = settings
    }

    override suspend fun lastCounter(type: DocumentType, period: Int): Long = counters[type to period] ?: 0

    override suspend fun setLastCounter(type: DocumentType, period: Int, counter: Long) {
        counters[type to period] = counter
    }
}

/** Items kept in a map. */
class InMemoryItemRepository : ItemRepository {
    private val items = LinkedHashMap<ItemId, Item>()

    override suspend fun find(id: ItemId): Item? = items[id]

    override suspend fun findByCode(code: String): Item? = items.values.firstOrNull { it.code == code }

    override suspend fun findByBarcode(barcode: String): Item? =
        items.values.firstOrNull { it.details.barcode == barcode }

    override suspend fun create(code: String, details: ItemDetails): Item {
        val item = Item(ItemId(items.size + 1L), code, details)
        items[item.id] = item
        return item
    }

    override suspend fun update(id: ItemId, details: ItemDetails) {
        items[id] = items.getValue(id).copy(details = details)
    }

    override suspend fun setArchived(id: ItemId, archived: Boolean) {
        items[id] = items.getValue(id).copy(archived = archived)
    }

    override suspend fun search(query: String, archived: Boolean, limit: Int): List<Item> {
        val wanted = SearchText.normalize(query)
        return items.values
            .filter { it.archived == archived }
            .filter { wanted in SearchText.of(it.code, it.details) }
            .sortedBy { SearchText.normalize(it.details.designation) }
            .take(limit)
    }

    override suspend fun count(archived: Boolean): Int = items.values.count { it.archived == archived }

    override suspend fun countWithoutVat(): Int =
        items.values.count { !it.archived && TaxKind.VAT !in it.details.taxes }
}

/** TIN register that answers from a map, or what the test forced, and remembers each call. */
class FakeTinLookup : TinLookup {
    /** The TINs the OBR knows, with the name of their owner. */
    val known = mutableMapOf<String, String>()

    /** Replies given to the next calls instead of the normal ones, oldest first. */
    val forced = mutableListOf<TinReply>()

    /** The token and the TIN of each call. */
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun check(target: LoginTarget, token: String, tin: String): TinReply {
        calls += token to tin
        return forced.removeFirstOrNull() ?: known[tin]?.let { TinReply.Known(it) } ?: TinReply.Unknown
    }
}

/** Token provider that always gives the same token. */
class FixedTokenProvider(var outcome: LoginOutcome = LoginOutcome.Success("token")) : TokenProvider {
    override suspend fun token(): LoginOutcome = outcome

    override fun invalidate() = Unit
}

/** Customers kept in a map. */
class InMemoryCustomerRepository : CustomerRepository {
    private val customers = LinkedHashMap<CustomerId, Customer>()

    override suspend fun find(id: CustomerId): Customer? = customers[id]

    override suspend fun findByTin(tin: String): Customer? =
        customers.values.firstOrNull { it.details.tin?.number == tin }

    override suspend fun counter(): Customer =
        customers.values.firstOrNull { it.counter } ?: stored(Customer.counterDetails(), counter = true)

    override suspend fun create(details: CustomerDetails): Customer = stored(details, counter = false)

    override suspend fun update(id: CustomerId, details: CustomerDetails) {
        customers[id] = customers.getValue(id).copy(details = details)
    }

    override suspend fun setArchived(id: CustomerId, archived: Boolean) {
        customers[id] = customers.getValue(id).copy(archived = archived)
    }

    override suspend fun search(query: String, archived: Boolean, limit: Int): List<Customer> {
        val wanted = SearchText.normalize(query)
        return customers.values
            .filter { !it.counter && it.archived == archived }
            .filter { wanted in CustomerSearchText.of(it.details) }
            .sortedBy { SearchText.normalize(it.details.name) }
            .take(limit)
    }

    override suspend fun count(archived: Boolean): Int =
        customers.values.count { !it.counter && it.archived == archived }

    private fun stored(details: CustomerDetails, counter: Boolean): Customer {
        val customer = Customer(CustomerId(customers.size + 1L), details, counter = counter)
        customers[customer.id] = customer
        return customer
    }
}
