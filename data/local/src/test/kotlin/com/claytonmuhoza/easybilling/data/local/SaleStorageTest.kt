package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.data.local.configuration.SecretStoreCredentialsVault
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationRules
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountLimits
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountApproval
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.DraftLine
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueConfiguration
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueStorage
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.Replacement
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineCheck
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.stock.InvoiceStock
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sales that are not issued, the issue of an invoice and what survives a crash, on a real
 * database file (UC-C01 steps 4 and 5, UC-C02).
 */
@RunWith(AndroidJUnit4::class)
class SaleStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name: see RoomItemRepositoryTest.
    private val name = "sales.db"
    private var storage = LocalStorage.unencryptedFile(context, name)
    private val secrets = object : SecretStore {
        private val values = mutableMapOf<String, ByteArray>()

        override fun read(name: String): ByteArray? = values[name]

        override fun write(name: String, value: ByteArray) {
            values[name] = value
        }
    }
    private var now = Instant.parse("2026-10-03T08:15:42Z")
    private val time = TimeProvider { now }
    private val alice = UserId(1)

    /** The write after which the next issue "crashes", or null. */
    private var crashAfter: String? = null

    private fun crashIf(point: String) {
        if (crashAfter == point) error("Simulated crash after $point")
    }

    private lateinit var soap: Item
    private lateinit var sugar: Item

    @Before
    fun configure() = runBlocking {
        val profile = TaxProfile(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            name = "Boutique Umuco SPRL",
            tin = "4000000001",
            tradeNumber = "RC 12345",
            postalNumber = "",
            phoneNumber = "+257 22 22 22 22",
            address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
            taxes = TaxRegistrations(vat = true),
            fiscalCenter = FiscalCenter.DMC,
            activitySector = "Commerce general",
            legalForm = "SPRL",
        )
        storage.taxProfiles.append(TaxProfileVersion(1, profile, now, alice))
        SecretStoreCredentialsVault(secrets)
            .write(EbmsEnvironment.TEST, EbmsCredentials("ws400000000100001", "password"))
        storage.ebmsSettings.write(EbmsSettings(lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to now)))
        storage.numbering.save(assertIs<NumberingCheck.Valid>(NumberingForm().check(null)).settings)
        val rate = TaxComputation.Percentage(BigDecimal("18"), PercentageBase.PRICE_WITHOUT_VAT)
        val vat = storage.taxes.create(TaxKind.VAT, "TVA 18 %", TaxVersion(LocalDate.of(2026, 10, 2), rate))
        fun details(designation: String, price: String, mode: PriceMode) = ItemDetails(
            designation = designation,
            unit = "pièce",
            price = Money.of(price, Currency.BIF),
            priceMode = mode,
            taxes = mapOf(TaxKind.VAT to vat.id),
            // The soap and the sugar are sold without stock: the oil of the stock test is kept in stock.
            stockTracked = false,
        )
        soap = storage.items.create("ART-0001", details("Savon de Marseille", "1000", PriceMode.EXCLUDING_TAXES))
        sugar = storage.items.create("ART-0002", details("Sucre 1 kg", "2360", PriceMode.INCLUDING_TAXES))
    }

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private fun edit() = EditSale(storage.saleDrafts, time, storage.transactions)

    /** The issue on the real storage, whose writes "crash" where [crashAfter] says. */
    private fun issue(): IssueSale {
        val vault = SecretStoreCredentialsVault(secrets)
        val readiness =
            CheckBillingReadiness(storage.taxProfiles, storage.ebmsSettings, storage.taxes, storage.numbering, time)
        val audit = ChainedAuditLog(storage.auditStore, time, storage.transactions)
        val invoices = object : InvoiceRepository by storage.invoices {
            override suspend fun create(content: InvoiceContent) =
                storage.invoices.create(content).also { crashIf("invoice") }
        }
        val outbox = object : OutboxRepository by storage.outbox {
            override suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry =
                storage.outbox.enqueue(operation, createdAt).also { crashIf("outbox") }
        }
        val drafts = object : SaleDraftRepository by storage.saleDrafts {
            override suspend fun delete(id: DraftId) = storage.saleDrafts.delete(id).also { crashIf("draft") }
        }
        val auditLog = object : AuditLog by audit {
            override suspend fun record(event: AuditEvent) = audit.record(event).also { crashIf("audit") }
        }
        val movements = object : StockRepository by storage.stock {
            override suspend fun record(content: StockMovementContent) =
                storage.stock.record(content).also { crashIf("stock") }
        }
        val invoiceStock = InvoiceStock(movements, storage.numbering, outbox, auditLog)
        return IssueSale(
            AccessControl { _, _ -> },
            IssueConfiguration(
                storage.taxProfiles,
                storage.ebmsSettings,
                vault,
                readiness,
                DiscountLimits(storage.users, storage.discountSettings, FixedPermissionPolicy),
                CancellationRules(storage.invoices, storage.cancellations, storage.saleDrafts),
            ),
            IssueStorage(drafts, invoices, outbox, auditLog, storage.cancellations, invoiceStock),
            PriceSale(storage.items, storage.taxes, storage.customers, storage.taxProfiles, time, invoiceStock),
            AllocateInvoiceNumber(storage.numbering, time, storage.transactions),
            storage.transactions,
            time,
        )
    }

    private suspend fun typedSale(): SaleDraft {
        edit().addItem(soap.id)
        edit().addItem(soap.id)
        return edit().addItem(sugar.id)
    }

    private suspend fun issued(draft: SaleDraft) = assertIs<IssueSale.Result.Issued>(issue()(draft.id, alice)).invoice

    @Test
    fun `a new database has no sale, no invoice and an empty queue`() = runTest {
        assertNull(storage.saleDrafts.current())
        assertEquals(emptyList(), storage.saleDrafts.held())
        assertEquals(0, storage.invoices.count())
        assertEquals(emptyList(), storage.outbox.pending())
    }

    @Test
    fun `a sale that is not issued is read back with every field`() = runTest {
        val customer = storage.customers.create(Customer.counterDetails("Aline Irakoze"))
        val created = storage.saleDrafts.create(now)
        val draft = created.copy(
            customerId = customer.id,
            paymentType = PaymentType.CREDIT,
            lines = listOf(
                DraftLine(
                    soap.id,
                    Quantity.of("2.5"),
                    Discount.Percent(BigDecimal("12.5")),
                    approval = DiscountApproval(UserId(7), Discount.Percent(BigDecimal("12.5"))),
                ),
                DraftLine(
                    sugar.id,
                    Quantity.of("1"),
                    Discount.PerUnit(BigDecimal("360")),
                    TaxKind.entries.filter { it != TaxKind.VAT }
                        .associateWith { Money.of("${it.ordinal}.5", Currency.BIF) },
                ),
                DraftLine(ItemId(99), Quantity.of("3")),
            ),
            updatedAt = Instant.parse("2026-10-03T09:00:00.123Z"),
            replacement = Replacement(InvoiceId(3), "Mauvais client", approvedBy = UserId(2)),
        )

        storage.saleDrafts.save(draft)

        assertEquals(draft, storage.saleDrafts.find(draft.id))
        assertEquals(draft, storage.saleDrafts.current())
        assertEquals(emptyList(), storage.saleDrafts.held())
    }

    @Test
    fun `sales on hold are listed apart, the most recent first, and deleted with their lines`() = runTest {
        val first = typedSale()
        edit().hold()
        now = now.plusSeconds(60)
        val second = edit().addItem(sugar.id)
        edit().hold()

        assertEquals(listOf(second.id, first.id), storage.saleDrafts.held().map { it.id })
        assertNull(storage.saleDrafts.current())

        storage.saleDrafts.delete(first.id)

        assertNull(storage.saleDrafts.find(first.id))
        assertEquals(listOf(second.id), storage.saleDrafts.held().map { it.id })
        val lines = storage.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sale_draft_lines")
        lines.use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun `an issued invoice is read back exactly, pending and queued for the OBR`() = runTest {
        val draft = typedSale()
        edit().setLine(soap.id, SaleLineCheck.Valid(Quantity.of("2"), Discount.Percent(BigDecimal("10")), emptyMap()))

        val invoice = issued(draft)

        assertEquals(invoice, storage.invoices.find(invoice.id))
        assertEquals(invoice, storage.invoices.findByNumber("FN-00001/2026"))
        assertEquals("4000000001/ws400000000100001/20261003101542/FN-00001/2026", invoice.content.identifier)
        assertEquals(Money.of("4484", Currency.BIF), storage.invoices.find(invoice.id)!!.content.totals.total)
        assertEquals(InvoiceStatus.Pending, storage.invoices.statusOf(invoice.id))
        assertEquals(
            listOf(OutboxOperation.SendInvoice(invoice.id) to invoice.content.issuedAt),
            storage.outbox.pending().map { it.operation to it.createdAt },
        )
        assertNull(storage.saleDrafts.find(draft.id))
        assertEquals(listOf("INVOICE_ISSUED"), storage.auditStore.readAll().map { it.action })
        assertTrue(storage.numbering.settings()!!.locked)
    }

    @Test
    fun `the database itself refuses any change to an issued invoice`() = runTest {
        val invoice = issued(typedSale())
        val database = storage.database.openHelper.writableDatabase

        for (statement in listOf(
            "UPDATE invoices SET customerName = 'Autre'",
            "DELETE FROM invoices",
            "UPDATE invoice_lines SET total = '1'",
            "DELETE FROM invoice_lines",
        )) {
            assertFailsWith<Exception>(statement) { database.execSQL(statement) }
        }
        assertEquals(invoice, storage.invoices.find(invoice.id))
    }

    @Test
    fun `the database itself refuses two invoices with the same number`() = runTest {
        val invoice = issued(typedSale())

        assertFailsWith<SQLiteConstraintException> { storage.invoices.create(invoice.content) }
        assertEquals(1, storage.invoices.count())
    }

    @Test
    fun `only the status of an invoice changes, along the allowed transitions`() = runTest {
        val invoice = issued(typedSale())
        val refusal = InvoiceStatus.Rejected("NIF du client n'est pas connu par l'OBR")

        storage.invoices.setStatus(invoice.id, refusal, now)
        assertEquals(refusal, storage.invoices.statusOf(invoice.id))
        assertFailsWith<IllegalStateException> { storage.invoices.setStatus(invoice.id, InvoiceStatus.Cancelled, now) }

        storage.invoices.setStatus(invoice.id, InvoiceStatus.Pending, now)
        storage.invoices.setStatus(invoice.id, InvoiceStatus.Registered, now)
        storage.invoices.setStatus(invoice.id, InvoiceStatus.Cancelled, now)

        assertEquals(InvoiceStatus.Cancelled, storage.invoices.statusOf(invoice.id))
        assertFailsWith<IllegalStateException> { storage.invoices.setStatus(invoice.id, InvoiceStatus.Pending, now) }
        assertNull(storage.invoices.statusOf(InvoiceId(99)))
        assertEquals(invoice, storage.invoices.find(invoice.id))
    }

    @Test
    fun `a crash at any write of the issue leaves no invoice and skips no number`() = runTest {
        val draft = typedSale()

        for (point in listOf("invoice", "outbox", "draft", "audit")) {
            crashAfter = point
            assertFailsWith<IllegalStateException>(point) { issue()(draft.id, alice) }

            // The application restarts on the same file.
            storage.close()
            storage = LocalStorage.unencryptedFile(context, name)
            assertEquals(0, storage.invoices.count(), point)
            assertEquals(emptyList(), storage.outbox.pending(), point)
            assertEquals(emptyList(), storage.auditStore.readAll(), point)
            assertEquals(0, storage.numbering.lastCounter(DocumentType.FN, 2026), point)
            assertEquals(draft, storage.saleDrafts.find(draft.id), point)
        }

        crashAfter = null
        assertEquals("FN-00001/2026", issued(draft).content.number)
        assertEquals("FN-00002/2026", issued(typedSale()).content.number)
    }

    @Test
    fun `the exit of stock of a sale is written with its invoice, or not at all`() = runTest {
        val oil = storage.items.create(
            "ART-0003",
            soap.details.copy(designation = "Huile de palme", stockTracked = true),
        )
        val opening = storage.stock.record(
            StockMovementContent(
                reference = "000001",
                itemId = oil.id,
                itemCode = oil.code,
                designation = oil.details.designation,
                unit = oil.details.unit,
                type = MovementType.EI,
                quantity = Quantity.of("5"),
                unitCost = Money.of("800", Currency.BIF),
                description = "",
                occurredAt = now,
                environment = EbmsEnvironment.TEST,
                recordedBy = alice,
            ),
        )
        edit().addItem(oil.id)
        val draft = edit().setLine(oil.id, SaleLineCheck.Valid(Quantity.of("2"), null, emptyMap()))

        crashAfter = "stock"
        assertFailsWith<IllegalStateException> { issue()(draft.id, alice) }

        // The application restarts on the same file.
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)
        assertEquals(0, storage.invoices.count())
        assertEquals(emptyList(), storage.outbox.pending())
        assertEquals(1L, storage.stock.count())
        assertEquals(0, BigDecimal("5").compareTo(storage.stock.quantityOf(oil.id)))

        crashAfter = null
        val invoice = issued(draft)

        assertEquals(invoice, storage.invoices.find(invoice.id))
        assertTrue(invoice.content.lines.single().stockTracked)
        val exit = storage.stock.ofInvoice(invoice.id).single()
        assertEquals(MovementType.SN, exit.content.type)
        assertEquals("000002", exit.content.reference)
        assertEquals(Quantity.of("2"), exit.content.quantity)
        assertEquals(Money.of("1180", Currency.BIF), exit.content.unitCost)
        assertEquals(listOf(exit, opening), storage.stock.movementsOf(oil.id, limit = 10))
        assertEquals(0, BigDecimal("3").compareTo(storage.stock.quantityOf(oil.id)))
        assertEquals(
            listOf(OutboxOperation.SendInvoice(invoice.id), OutboxOperation.SendStockMovement(exit.id)),
            storage.outbox.pending().map { it.operation },
        )
        val audited = storage.auditStore.readAll().map { it.action }
        assertEquals(listOf("STOCK_MOVEMENT_RECORDED", "INVOICE_ISSUED"), audited)

        // A sale of more than what is left is refused, and writes nothing.
        edit().addItem(oil.id)
        val tooMuch = edit().setLine(oil.id, SaleLineCheck.Valid(Quantity.of("4"), null, emptyMap()))
        assertIs<IssueSale.Result.Invalid>(issue()(tooMuch.id, alice))
        assertEquals(1, storage.invoices.count())
    }

    @Test
    fun `a refused issue gives its number back`() = runTest {
        val draft = typedSale()
        // The longest system identifier and a long NIF leave 13 characters: "A1-FN-00001/2026" has 16.
        val vault = SecretStoreCredentialsVault(secrets)
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("w".repeat(30), "password"))
        val current = storage.taxProfiles.current()!!
        storage.taxProfiles.append(current.copy(number = 2, profile = current.profile.copy(tin = "4".repeat(30))))
        val devices = NumberingForm.defaultFor(multipleDevices = true).copy(deviceCode = "A1")
        storage.numbering.save(assertIs<NumberingCheck.Valid>(devices.check(null)).settings)

        assertEquals(IssueSale.Result.IdentifierTooLong, issue()(draft.id, alice))

        assertEquals(0, storage.invoices.count())
        assertEquals(0, storage.numbering.lastCounter(DocumentType.FN, 2026))
        assertEquals(draft, storage.saleDrafts.find(draft.id))
    }

    @Test
    fun `fifty sales in a row have fifty numbers without gap`() = runTest {
        val numbers = List(SALES) {
            now = now.plusSeconds(1)
            issued(edit().addItem(soap.id)).content.number
        }

        assertEquals(List(SALES) { "FN-%05d/2026".format(it + 1) }, numbers)
        assertEquals(SALES, storage.invoices.count())
        assertEquals(SALES, storage.outbox.pending().size)
    }

    @Test
    fun `an invoice of 20 lines is issued in less than a second`() {
        val draft = runBlocking {
            // A first sale opens the database and loads the classes: it is not what is measured.
            issued(edit().addItem(soap.id))
            val template = storage.items.find(soap.id)!!.details
            storage.transactions.run {
                repeat(LINES) { rank ->
                    val item = storage.items.create("L-%04d".format(rank), template.copy(designation = "Article $rank"))
                    edit().addItem(item.id)
                }
            }
            edit().current()
        }

        val millis = measureTimeMillis { runBlocking { issued(draft) } }

        println("Issue of an invoice of $LINES lines: $millis ms")
        assertTrue(millis < ONE_SECOND_MILLIS, "The issue took $millis ms")
        assertEquals(LINES, runBlocking { storage.invoices.findByNumber("FN-00002/2026") }!!.content.lines.size)
        assertNull(runBlocking { storage.customers.find(CustomerId(99)) })
    }

    private companion object {
        const val SALES = 50
        const val LINES = 20
        const val ONE_SECOND_MILLIS = 1_000L
    }
}
