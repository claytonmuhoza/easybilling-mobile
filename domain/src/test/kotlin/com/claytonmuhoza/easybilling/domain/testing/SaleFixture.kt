package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.SearchText
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
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CancelInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationRules
import com.claytonmuhoza.easybilling.domain.invoicing.CorrectionAuthority
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNoteRules
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNoteStorage
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentStamper
import com.claytonmuhoza.easybilling.domain.invoicing.IssueCreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellation
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.RedoSale
import com.claytonmuhoza.easybilling.domain.invoicing.ReplaceInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.ResendRejectedInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountLimits
import com.claytonmuhoza.easybilling.domain.invoicing.SaveDiscountSettings
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceFilter
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.IssueConfiguration
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueStorage
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.stock.InvoiceStock
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.transmission.CancelOutcome
import com.claytonmuhoza.easybilling.domain.transmission.DmcOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.LookupOutcome
import com.claytonmuhoza.easybilling.domain.transmission.MovementOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrGateway
import com.claytonmuhoza.easybilling.domain.transmission.ObrInvoiceCopy
import com.claytonmuhoza.easybilling.domain.transmission.ObrMessages
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import java.math.BigDecimal
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
class InMemoryInvoiceRepository :
    InvoiceRepository,
    InvoiceListing {
    private val invoices = LinkedHashMap<InvoiceId, Invoice>()
    private val statuses = mutableMapOf<InvoiceId, InvoiceStatus>()
    private val changes = mutableMapOf<InvoiceId, Instant>()

    override suspend fun create(content: InvoiceContent): Invoice {
        val invoice = Invoice(InvoiceId(invoices.size + 1L), content)
        invoices[invoice.id] = invoice
        statuses[invoice.id] = InvoiceStatus.Pending
        return invoice
    }

    override suspend fun find(id: InvoiceId): Invoice? = invoices[id]

    override suspend fun findByNumber(number: String): Invoice? =
        invoices.values.firstOrNull { it.content.number == number }

    override suspend fun creditNotesOf(number: String): List<Invoice> =
        invoices.values.filter { it.content.credit?.invoiceRef == number }

    override suspend fun statusOf(id: InvoiceId): InvoiceStatus? = statuses[id]

    override suspend fun setStatus(id: InvoiceId, status: InvoiceStatus, at: Instant) {
        check(statuses.getValue(id).canBecome(status)) { "${statuses[id]} cannot become $status" }
        statuses[id] = status
        changes[id] = at
    }

    override suspend fun rejected(): List<RejectedDocument> = statuses
        .mapNotNull { (id, status) -> (status as? InvoiceStatus.Rejected)?.let { id to it.message } }
        .sortedByDescending { (id, _) -> changes.getValue(id) }
        .map { (id, message) -> RejectedDocument(invoices.getValue(id), message) }

    override suspend fun countRegisteredSince(since: Instant): Int =
        statuses.count { (id, status) -> status == InvoiceStatus.Registered && changes.getValue(id) >= since }

    override suspend fun count(): Int = invoices.size

    override suspend fun countIn(environment: EbmsEnvironment): Int =
        invoices.values.count { it.content.environment == environment }

    override suspend fun replaceSeller(id: InvoiceId, profileVersion: Int, identifier: String): Invoice {
        check(statuses.getValue(id) is InvoiceStatus.Rejected) { "Invoice ${id.value} is not refused" }
        val invoice = invoices.getValue(id)
        val content = invoice.content.copy(profileVersion = profileVersion, identifier = identifier)
        return invoice.copy(content = content).also { invoices[id] = it }
    }

    override suspend fun search(filter: InvoiceFilter, limit: Int): List<InvoiceSummary> =
        matching(filter)
            .filter { filter.status == null || it.status.kind == filter.status }
            .sortedWith(compareByDescending<InvoiceSummary> { it.issuedAt }.thenByDescending { it.id.value })
            .take(limit)

    override suspend fun countByStatus(filter: InvoiceFilter): Map<InvoiceStatusKind, Int> =
        matching(filter).groupingBy { it.status.kind }.eachCount()

    private fun matching(filter: InvoiceFilter): List<InvoiceSummary> {
        val wanted = SearchText.normalize(filter.query)
        return invoices.values
            .filter { filter.since == null || it.content.issuedAt >= filter.since }
            .filter { filter.type == null || it.content.type == filter.type }
            .filter { wanted in SearchText.normalize(it.content.number + " " + it.content.customer.name) }
            .map {
                InvoiceSummary(
                    id = it.id,
                    type = it.content.type,
                    number = it.content.number,
                    issuedAt = it.content.issuedAt,
                    customerName = it.content.customer.name,
                    paymentType = it.content.paymentType,
                    total = it.content.totals.total,
                    status = statuses.getValue(it.id),
                )
            }
    }
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

    /** What the OBR gives for an identifier it has, when it is not the invoice as it was sent. */
    val copies = mutableMapOf<String, ObrInvoiceCopy>()

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
            copies[invoice.content.identifier] = copyOf(invoice)
            SendOutcome.Accepted(receiptOf(invoice))
        } else {
            SendOutcome.Rejected(ObrMessages.DUPLICATE_NUMBER)
        }
    }

    override suspend fun getInvoice(target: LoginTarget, token: String, identifier: String): LookupOutcome {
        calls += Triple("getInvoice", token, identifier)
        return forcedLookups.removeFirstOrNull()
            ?: if (identifier in known) found(identifier) else LookupOutcome.NotFound
    }

    /** Outcomes given to the next cancellations instead of the normal ones, oldest first. */
    val forcedCancels = mutableListOf<CancelOutcome>()

    /** The `cn_motif` of each cancellation, in order. */
    val cancelReasons = mutableListOf<String>()

    override suspend fun cancelInvoice(
        target: LoginTarget,
        token: String,
        identifier: String,
        reason: String,
    ): CancelOutcome {
        calls += Triple("cancelInvoice", token, identifier)
        cancelReasons += reason
        return forcedCancels.removeFirstOrNull() ?: if (identifier in known) {
            copies[identifier]?.let { copies[identifier] = it.copy(cancelled = true) }
            CancelOutcome.Cancelled
        } else {
            CancelOutcome.Rejected("Identifiant de la facture inconnu")
        }
    }

    /** The lines of the customs declarations the OBR knows, by reference; they belong to any NIF. */
    val dmcs = mutableMapOf<String, List<DmcLine>>()

    /** Outcomes given to the next readings of a declaration instead of the normal one, oldest first. */
    val forcedDmcs = mutableListOf<DmcOutcome>()

    override suspend fun getDmcItems(target: LoginTarget, token: String, tin: String, reference: String): DmcOutcome {
        calls += Triple("getDmcItems", tin, reference)
        return forcedDmcs.removeFirstOrNull()
            ?: dmcs[reference]?.let { DmcOutcome.Found(it) }
            ?: DmcOutcome.Refused("La reference DMC n'appartient pas a ce NIF")
    }

    /** Outcomes given to the next stock movements instead of the normal one, oldest first. */
    val forcedMovements = mutableListOf<MovementOutcome>()

    /** The stock movements the OBR accepted, in order. */
    val movements = mutableListOf<StockMovement>()

    override suspend fun addStockMovement(
        target: LoginTarget,
        token: String,
        movement: StockMovement,
    ): MovementOutcome {
        calls += Triple("addStockMovement", token, movement.content.reference)
        return forcedMovements.removeFirstOrNull() ?: MovementOutcome.Accepted.also { movements += movement }
    }

    /** Makes the OBR have [invoice] as it was issued, without a call. */
    fun accept(invoice: Invoice) {
        known += invoice.content.identifier
        copies[invoice.content.identifier] = copyOf(invoice)
    }

    /** @return [invoice] as this OBR registers it. */
    fun copyOf(invoice: Invoice) = ObrInvoiceCopy(
        number = invoice.content.number,
        date = invoice.content.issuedAt.toString(),
        customerName = invoice.content.customer.name,
        itemCount = invoice.content.lines.size,
        total = invoice.content.totals.total.amount,
        cancelled = false,
    )

    /** The copy of an invoice; one the test only gave the identifier of says nothing else. */
    private fun found(identifier: String) = LookupOutcome.Found(
        copies[identifier] ?: ObrInvoiceCopy(
            number = identifier.split("/", limit = IDENTIFIER_PARTS).last(),
            date = "",
            customerName = "",
            itemCount = 0,
            total = null,
            cancelled = null,
        ),
    )

    /** @return the acknowledgement this OBR gives to [invoice]. */
    fun receiptOf(invoice: Invoice) = ObrReceipt(
        registeredNumber = "REG-${invoice.content.number}",
        registeredDate = "2026-10-03 10:00:00",
        signature = "signature-of-${invoice.content.number}",
        rawResponse = """{"success":true}""",
    )

    private companion object {
        /** NIF, system identifier, date and number. */
        const val IDENTIFIER_PARTS = 4
    }
}

/**
 * A business ready to sell, in memory: tax profile registered for the VAT, EBMS connection tested,
 * default numbering, a VAT of 18 % and the use cases of the sale. Tests change what they need.
 */
class SaleFixture {
    val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00Z"))
    val users = InMemoryUserRepository()
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
    val stock = InMemoryStockRepository()
    val invoiceStock = InvoiceStock(stock, numbering, outbox, audit)

    val priceSale = PriceSale(items, taxes, customers, profiles, time, invoiceStock)
    val editSale = EditSale(drafts, time, DirectTransactionRunner)
    val access = FakeAccessControl()
    val discountSettings = InMemoryDiscountSettingsRepository()
    val discountLimits = DiscountLimits(users, discountSettings, FixedPermissionPolicy)
    val saveDiscountSettings = SaveDiscountSettings(access, discountSettings, audit)
    val cancellations = InMemoryInvoiceCancellationRepository()
    val cancellationRules = CancellationRules(invoices, cancellations, drafts)
    private val authority = CorrectionAuthority(access, users, FixedPermissionPolicy)
    val cancelInvoice = CancelInvoice(
        cancellationRules,
        authority,
        cancellations,
        outbox,
        audit,
        DirectTransactionRunner,
        time,
    )
    val creditRules = CreditNoteRules(invoices, cancellations, drafts)
    val replaceInvoice = ReplaceInvoice(
        cancellationRules,
        authority,
        RedoSale(invoices, items, customers, drafts, DirectTransactionRunner, time),
        audit,
    )
    val issueSale = IssueSale(
        access,
        IssueConfiguration(
            profiles,
            ebmsSettings,
            vault,
            CheckBillingReadiness(profiles, ebmsSettings, taxes, numbering, time),
            discountLimits,
            cancellationRules,
        ),
        IssueStorage(drafts, invoices, outbox, audit, cancellations, invoiceStock),
        priceSale,
        AllocateInvoiceNumber(numbering, time, DirectTransactionRunner),
        DirectTransactionRunner,
        time,
    )

    private val readiness = CheckBillingReadiness(profiles, ebmsSettings, taxes, numbering, time)
    val issueCreditNote = IssueCreditNote(
        creditRules,
        authority,
        DocumentStamper(
            profiles,
            ebmsSettings,
            vault,
            readiness,
            AllocateInvoiceNumber(numbering, time, DirectTransactionRunner),
        ),
        CreditNoteStorage(invoices, outbox, audit, invoiceStock),
        DirectTransactionRunner,
        time,
    )

    val resendRejected = ResendRejectedInvoice(
        access,
        invoices,
        profiles,
        outbox,
        invoiceStock,
        audit,
        DirectTransactionRunner,
        time,
    )

    /** The VAT of 18 %, in force since the day before the tests. */
    lateinit var vat: TaxDefinition
        private set

    /** Makes the business ready to issue invoices. */
    suspend fun ready(registrations: TaxRegistrations = TaxRegistrations(vat = true)) {
        if (users.find(alice) == null) {
            val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
            users.create(NewUser("Alice", setOf(Role.CASHIER), secret))
        }
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

    /**
     * Stores an item with the VAT of 18 %, unless [taxes] says otherwise. It is not kept in stock,
     * unless [stockTracked]: the tests that are not about the stock sell without it. [stock] is
     * what the item has in stock before the test: no movement tells of it.
     */
    suspend fun item(
        code: String,
        price: String,
        priceMode: PriceMode = PriceMode.EXCLUDING_TAXES,
        taxes: Map<TaxKind, TaxDefinition> = mapOf(TaxKind.VAT to vat),
        stockTracked: Boolean = false,
        stock: String? = null,
    ): Item = items.create(
        code,
        ItemDetails(
            designation = "Article $code",
            unit = "pièce",
            price = Money.of(price, Currency.BIF),
            priceMode = priceMode,
            taxes = taxes.mapValues { it.value.id },
            stockTracked = stockTracked,
        ),
    ).also { item -> if (stock != null) this.stock.opening[item.id] = BigDecimal(stock) }
}

/** Cancellations kept in a map, one per invoice. */
class InMemoryInvoiceCancellationRepository : InvoiceCancellationRepository {
    private val cancellations = mutableMapOf<InvoiceId, InvoiceCancellation>()

    override suspend fun save(cancellation: InvoiceCancellation) {
        cancellations[cancellation.invoiceId] = cancellation
    }

    override suspend fun find(invoiceId: InvoiceId): InvoiceCancellation? = cancellations[invoiceId]
}
