package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueConfiguration
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueStorage
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.LookupOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrGateway
import com.claytonmuhoza.easybilling.domain.transmission.ObrMessages
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertIs

/** Sales that are not issued, kept in a map. */
class InMemorySaleDraftRepository : SaleDraftRepository {
    private val drafts = LinkedHashMap<DraftId, SaleDraft>()
    private var lastId = 0L

    override suspend fun create(now: Instant): SaleDraft =
        SaleDraft(DraftId(++lastId), updatedAt = now).also { drafts[it.id] = it }

    override suspend fun find(id: DraftId): SaleDraft? = drafts[id]

    override suspend fun current(): SaleDraft? = drafts.values.firstOrNull { !it.held }

    override suspend fun held(): List<SaleDraft> =
        drafts.values.filter { it.held }.sortedByDescending { it.updatedAt }

    override suspend fun save(draft: SaleDraft) {
        drafts[draft.id] = draft
    }

    override suspend fun delete(id: DraftId) {
        drafts.remove(id)
    }
}

/** Issued invoices kept in a map. */
class InMemoryInvoiceRepository : InvoiceRepository {
    private val invoices = LinkedHashMap<InvoiceId, Invoice>()
    private val statuses = mutableMapOf<InvoiceId, InvoiceStatus>()

    override suspend fun create(content: InvoiceContent): Invoice {
        val invoice = Invoice(InvoiceId(invoices.size + 1L), content)
        invoices[invoice.id] = invoice
        statuses[invoice.id] = InvoiceStatus.Pending
        return invoice
    }

    override suspend fun find(id: InvoiceId): Invoice? = invoices[id]

    override suspend fun findByNumber(number: String): Invoice? =
        invoices.values.firstOrNull { it.content.number == number }

    override suspend fun statusOf(id: InvoiceId): InvoiceStatus? = statuses[id]

    override suspend fun setStatus(id: InvoiceId, status: InvoiceStatus, at: Instant) {
        check(statuses.getValue(id).canBecome(status)) { "${statuses[id]} cannot become $status" }
        statuses[id] = status
    }

    override suspend fun count(): Int = invoices.size

    override suspend fun countIn(environment: EbmsEnvironment): Int =
        invoices.values.count { it.content.environment == environment }
}

/** Send queue kept in a list. */
class InMemoryOutboxRepository : OutboxRepository {
    private val entries = mutableListOf<OutboxEntry>()

    override suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry =
        OutboxEntry(entries.size + 1L, operation, createdAt).also { entries += it }

    override suspend fun pending(): List<OutboxEntry> = entries.toList()

    override suspend fun update(entry: OutboxEntry) {
        entries.replaceAll { if (it.id == entry.id) entry else it }
    }

    override suspend fun remove(id: Long) {
        entries.removeAll { it.id == id }
    }
}

/** Acknowledgements kept in a map. */
class InMemoryReceiptArchive : ReceiptArchive {
    val receipts = mutableMapOf<InvoiceId, ObrReceipt>()

    override suspend fun archive(invoice: InvoiceId, receipt: ObrReceipt, receivedAt: Instant) {
        check(invoice !in receipts) { "Invoice ${invoice.value} already has an acknowledgement" }
        receipts[invoice] = receipt
    }

    override suspend fun find(invoice: InvoiceId): ObrReceipt? = receipts[invoice]
}

/**
 * OBR that keeps the invoices it accepts, answers what the test forced instead, and remembers
 * each call with its token.
 */
class FakeObrGateway : ObrGateway {
    /** The identifiers of the invoices the OBR has. */
    val known = mutableSetOf<String>()

    /** Outcomes given to the next sends instead of the normal ones, oldest first. */
    val forcedSends = mutableListOf<SendOutcome>()

    /** Outcomes given to the next lookups instead of the normal ones, oldest first. */
    val forcedLookups = mutableListOf<LookupOutcome>()

    /** Each call: the service, the token and the number or the identifier of the invoice. */
    val calls = mutableListOf<Triple<String, String, String>>()

    /** The tax profile each invoice was sent with. */
    val sellers = mutableListOf<TaxProfile>()

    /** Whether a forced [SendOutcome.Uncertain] reaches the OBR although its answer is lost. */
    var lostAnswerReachesObr = false

    override suspend fun addInvoice(
        target: LoginTarget,
        token: String,
        invoice: Invoice,
        seller: TaxProfile,
    ): SendOutcome {
        calls += Triple("addInvoice", token, invoice.content.number)
        sellers += seller
        val forced = forcedSends.removeFirstOrNull()
        if (forced is SendOutcome.Uncertain && lostAnswerReachesObr) known += invoice.content.identifier
        return forced ?: if (known.add(invoice.content.identifier)) {
            SendOutcome.Accepted(receiptOf(invoice))
        } else {
            SendOutcome.Rejected(ObrMessages.DUPLICATE_NUMBER)
        }
    }

    override suspend fun getInvoice(target: LoginTarget, token: String, identifier: String): LookupOutcome {
        calls += Triple("getInvoice", token, identifier)
        return forcedLookups.removeFirstOrNull()
            ?: if (identifier in known) LookupOutcome.Found else LookupOutcome.NotFound
    }

    /** @return the acknowledgement this OBR gives to [invoice]. */
    fun receiptOf(invoice: Invoice) = ObrReceipt(
        registeredNumber = "REG-${invoice.content.number}",
        registeredDate = "2026-10-03 10:00:00",
        signature = "signature-of-${invoice.content.number}",
        rawResponse = """{"success":true}""",
    )
}

/**
 * A business ready to sell, in memory: tax profile registered for the VAT, EBMS connection tested,
 * default numbering, a VAT of 18 % and the use cases of the sale. Tests change what they need.
 */
class SaleFixture {
    val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00Z"))
    val alice = UserId(1)
    val items = InMemoryItemRepository()
    val taxes = InMemoryTaxRepository()
    val customers = InMemoryCustomerRepository()
    val profiles = InMemoryTaxProfileRepository()
    val numbering = InMemoryNumberingRepository()
    val ebmsSettings = InMemoryEbmsSettingsRepository()
    val vault = InMemoryCredentialsVault()
    val drafts = InMemorySaleDraftRepository()
    val invoices = InMemoryInvoiceRepository()
    val outbox = InMemoryOutboxRepository()
    val audit = RecordingAuditLog()

    val priceSale = PriceSale(items, taxes, customers, profiles, time)
    val editSale = EditSale(drafts, time, DirectTransactionRunner)
    val issueSale = IssueSale(
        IssueConfiguration(
            profiles,
            ebmsSettings,
            vault,
            CheckBillingReadiness(profiles, ebmsSettings, taxes, numbering, time),
        ),
        IssueStorage(drafts, invoices, outbox, audit),
        priceSale,
        AllocateInvoiceNumber(numbering, time, DirectTransactionRunner),
        DirectTransactionRunner,
        time,
    )

    /** The VAT of 18 %, in force since the day before the tests. */
    lateinit var vat: TaxDefinition
        private set

    /** Makes the business ready to issue invoices. */
    suspend fun ready(registrations: TaxRegistrations = TaxRegistrations(vat = true)) {
        saveProfile(registrations)
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("ws400000000100001", "password"))
        ebmsSettings.write(EbmsSettings(lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to time.now())))
        numbering.save(assertIs<NumberingCheck.Valid>(NumberingForm().check(null)).settings)
        vat = tax(TaxKind.VAT, "TVA 18 %", percent("18", PercentageBase.PRICE_WITHOUT_VAT))
    }

    /** Saves a new version of the tax profile with [registrations]. */
    suspend fun saveProfile(registrations: TaxRegistrations) {
        val form = TaxProfileSamples.form().copy(taxes = registrations)
        val profile = assertIs<TaxProfileCheck.Valid>(form.check()).profile
        profiles.append(TaxProfileVersion((profiles.current()?.number ?: 0) + 1, profile, time.now(), alice))
    }

    /** Defines a tax in force since the day before the tests. */
    suspend fun tax(kind: TaxKind, name: String, computation: TaxComputation): TaxDefinition =
        taxes.create(kind, name, TaxVersion(LocalDate.of(2026, 10, 2), computation))

    /** Stores an item with the VAT of 18 %, unless [taxes] says otherwise. */
    suspend fun item(
        code: String,
        price: String,
        priceMode: PriceMode = PriceMode.EXCLUDING_TAXES,
        taxes: Map<TaxKind, TaxDefinition> = mapOf(TaxKind.VAT to vat),
    ): Item = items.create(
        code,
        ItemDetails(
            designation = "Article $code",
            unit = "pièce",
            price = Money.of(price, Currency.BIF),
            priceMode = priceMode,
            taxes = taxes.mapValues { it.value.id },
            stockTracked = true,
        ),
    )
}
