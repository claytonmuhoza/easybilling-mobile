package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceFilter
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The list of the invoices and the seller a refused invoice is sent again with, on a real database
 * file (UC-C08, UC-D04).
 */
@RunWith(AndroidJUnit4::class)
class InvoiceTrackingStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name, and short test names: see RoomItemRepositoryTest.
    private val name = "tracking.db"
    private var storage = LocalStorage.unencryptedFile(context, name)
    private val morning = Instant.parse("2026-10-03T08:00:00Z")
    private var issued = 0

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    /** A line whose total is [total]; the other amounts do not matter here. */
    private fun line(total: String) = InvoiceLine(
        itemId = ItemId(1),
        itemCode = "ART-0001",
        designation = "Savon de Marseille",
        unit = "pièce",
        stockTracked = true,
        quantity = Quantity.of("1"),
        listUnitPrice = bif(total),
        priceMode = PriceMode.INCLUDING_TAXES,
        discount = null,
        vatRate = BigDecimal("18"),
        amounts = LineAmounts(
            unitPrice = bif(total),
            price = bif(total),
            consumptionTax = bif("0"),
            ott = bif("0"),
            tsce = bif("0"),
            priceWithoutVat = bif(total),
            vat = bif("0"),
            priceWithVat = bif(total),
            flatRateWithholding = bif("0"),
            total = bif(total),
        ),
    )

    /** Stores an invoice issued [minutes] after the morning, with two lines of [lineTotal]. */
    private suspend fun invoice(
        customer: String = "Client comptoir",
        minutes: Long = 0,
        lineTotal: String = "1180.5",
        payment: PaymentType = PaymentType.CASH,
    ): Invoice {
        val number = "FN-%05d/2026".format(++issued)
        return storage.invoices.create(
            InvoiceContent(
                type = DocumentType.FN,
                number = number,
                identifier = "4000000001/ws400000000100001/20261003100000/$number",
                issuedAt = morning.plusSeconds(minutes * SECONDS_PER_MINUTE),
                profileVersion = 1,
                environment = EbmsEnvironment.TEST,
                customer = InvoiceCustomer(CustomerId(1), customer, null, "", false),
                paymentType = payment,
                currency = Currency.BIF,
                lines = listOf(line(lineTotal), line(lineTotal)),
                issuedBy = UserId(1),
            ),
        )
    }

    private suspend fun numbers(filter: InvoiceFilter = InvoiceFilter(), limit: Int = 100) =
        storage.invoiceListing.search(filter, limit).map { it.number }

    @Test
    fun `the invoices are listed the most recent first, with their total and their status`() = runTest {
        val first = invoice(customer = "Ets Kaneza", minutes = 0, payment = PaymentType.CREDIT)
        val second = invoice(minutes = 10)
        storage.invoices.setStatus(second.id, InvoiceStatus.Rejected("Le NIF est invalide."), morning)

        val listed = storage.invoiceListing.search(InvoiceFilter(), limit = 100)

        assertEquals(listOf("FN-00002/2026", "FN-00001/2026"), listed.map { it.number })
        assertEquals(
            InvoiceSummary(
                id = first.id,
                type = DocumentType.FN,
                number = "FN-00001/2026",
                issuedAt = morning,
                customerName = "Ets Kaneza",
                paymentType = PaymentType.CREDIT,
                total = bif("2361"),
                status = InvoiceStatus.Pending,
            ),
            listed[1],
        )
        assertEquals(InvoiceStatus.Rejected("Le NIF est invalide."), listed[0].status)
        assertEquals(first.content.totals.total, listed[1].total)
    }

    @Test
    fun `an invoice is found by a part of its number or of the name of its customer`() = runTest {
        invoice(customer = "Société Générale d'Électricité")
        invoice(customer = "Ets Kaneza")
        invoice(customer = "100% Café")

        assertEquals(listOf("FN-00001/2026"), numbers(InvoiceFilter(query = "generale d'electricite")))
        assertEquals(listOf("FN-00002/2026"), numbers(InvoiceFilter(query = "  KANEZA ")))
        assertEquals(listOf("FN-00002/2026"), numbers(InvoiceFilter(query = "fn-00002")))
        assertEquals(listOf("FN-00003/2026"), numbers(InvoiceFilter(query = "100%")))
        assertEquals(emptyList(), numbers(InvoiceFilter(query = "_")))
        assertEquals(3, numbers(InvoiceFilter(query = "2026")).size)
    }

    @Test
    fun `the invoices are filtered by status and period, up to the limit`() = runTest {
        val old = invoice(minutes = 0)
        val registered = invoice(minutes = 60)
        val rejected = invoice(minutes = 120)
        invoice(minutes = 180)
        storage.invoices.setStatus(old.id, InvoiceStatus.Registered, morning)
        storage.invoices.setStatus(registered.id, InvoiceStatus.Registered, morning)
        storage.invoices.setStatus(rejected.id, InvoiceStatus.Rejected("refused"), morning)
        val sinceSecond = registered.content.issuedAt

        assertEquals(
            listOf("FN-00002/2026", "FN-00001/2026"),
            numbers(InvoiceFilter(status = InvoiceStatusKind.REGISTERED)),
        )
        assertEquals(listOf("FN-00003/2026"), numbers(InvoiceFilter(status = InvoiceStatusKind.REJECTED)))
        assertEquals(emptyList(), numbers(InvoiceFilter(status = InvoiceStatusKind.CANCELLED)))
        assertEquals(
            listOf("FN-00004/2026", "FN-00003/2026", "FN-00002/2026"),
            numbers(InvoiceFilter(since = sinceSecond)),
        )
        assertEquals(
            listOf("FN-00002/2026"),
            numbers(InvoiceFilter(status = InvoiceStatusKind.REGISTERED, since = sinceSecond)),
        )
        assertEquals(listOf("FN-00004/2026", "FN-00003/2026"), numbers(limit = 2))
    }

    @Test
    fun `the invoices are counted by status, whatever the status asked for`() = runTest {
        val first = invoice(customer = "Ets Kaneza", minutes = 0)
        val second = invoice(customer = "Ets Kaneza", minutes = 60)
        invoice(minutes = 120)
        storage.invoices.setStatus(first.id, InvoiceStatus.Registered, morning)
        storage.invoices.setStatus(second.id, InvoiceStatus.Rejected("refused"), morning)

        assertEquals(
            mapOf(InvoiceStatusKind.REGISTERED to 1, InvoiceStatusKind.REJECTED to 1, InvoiceStatusKind.PENDING to 1),
            storage.invoiceListing.countByStatus(InvoiceFilter(status = InvoiceStatusKind.REJECTED)),
        )
        assertEquals(
            mapOf(InvoiceStatusKind.REGISTERED to 1, InvoiceStatusKind.REJECTED to 1),
            storage.invoiceListing.countByStatus(InvoiceFilter(query = "kaneza")),
        )
        assertEquals(
            mapOf(InvoiceStatusKind.REJECTED to 1, InvoiceStatusKind.PENDING to 1),
            storage.invoiceListing.countByStatus(InvoiceFilter(since = second.content.issuedAt)),
        )
        assertEquals(emptyMap(), storage.invoiceListing.countByStatus(InvoiceFilter(query = "introuvable")))
    }

    @Test
    fun `a refused invoice is read with its corrected seller`() = runTest {
        val invoice = invoice()
        val corrected = "4000999999/ws400000000100001/20261003100000/FN-00001/2026"
        storage.invoices.setStatus(invoice.id, InvoiceStatus.Rejected("Le NIF est invalide."), morning)

        val resent = storage.invoices.replaceSeller(invoice.id, profileVersion = 2, identifier = corrected)

        assertEquals(invoice.content.copy(profileVersion = 2, identifier = corrected), resent.content)
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)
        assertEquals(resent, storage.invoices.find(invoice.id))
        assertEquals(resent, storage.invoices.findByNumber("FN-00001/2026"))
        assertEquals(resent, storage.invoices.rejected().single().invoice)
        // The issued row is append only: it still holds what was issued.
        storage.database.openHelper.readableDatabase
            .query("SELECT identifier, profileVersion FROM invoices").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(invoice.content.identifier, rows.getString(0))
                assertEquals(1, rows.getInt(1))
            }

        // A second correction replaces the first.
        val again = storage.invoices.replaceSeller(invoice.id, profileVersion = 3, identifier = corrected)
        assertEquals(3, storage.invoices.find(invoice.id)!!.content.profileVersion)
        assertEquals(again, storage.invoices.find(invoice.id))
    }

    @Test
    fun `the seller of an invoice that is not refused is never replaced`() = runTest {
        val invoice = invoice()

        assertFailsWith<IllegalStateException> { storage.invoices.replaceSeller(invoice.id, 2, "other") }
        storage.invoices.setStatus(invoice.id, InvoiceStatus.Registered, morning)
        assertFailsWith<IllegalStateException> { storage.invoices.replaceSeller(invoice.id, 2, "other") }

        assertEquals(invoice, storage.invoices.find(invoice.id))
    }

    @Test
    fun `a search among 10 000 invoices takes less than a second`() {
        runBlocking {
            storage.transactions.run {
                repeat(INVOICE_COUNT) { rank ->
                    val customer = "Client ${CUSTOMERS[rank % CUSTOMERS.size]} $rank"
                    val created = invoice(customer = customer, minutes = rank.toLong())
                    if (rank % REJECTED_EVERY == 0) {
                        storage.invoices.setStatus(created.id, InvoiceStatus.Rejected("refused"), morning)
                    }
                }
            }
        }

        val filters = listOf(
            InvoiceFilter(),
            InvoiceFilter(query = "fn-09999"),
            InvoiceFilter(query = "kaneza 7654"),
            InvoiceFilter(query = "introuvable"),
            InvoiceFilter(status = InvoiceStatusKind.REJECTED),
            InvoiceFilter(query = "9", since = morning.plusSeconds(HALF * SECONDS_PER_MINUTE)),
        )
        for (filter in filters) {
            val millis = measureTimeMillis {
                runBlocking {
                    storage.invoiceListing.search(filter, limit = 100)
                    storage.invoiceListing.countByStatus(filter)
                }
            }
            assertTrue(millis < ONE_SECOND_MILLIS, "$filter took $millis ms")
            println("Search $filter among $INVOICE_COUNT invoices: $millis ms")
        }
        val counted = runBlocking { storage.invoiceListing.countByStatus(InvoiceFilter()) }
        val found = runBlocking { storage.invoiceListing.search(InvoiceFilter(query = "fn-09999"), limit = 100) }
        assertEquals(INVOICE_COUNT, counted.values.sum())
        assertEquals(1, found.size)
    }

    private companion object {
        const val INVOICE_COUNT = 10_000
        const val HALF = 5_000L
        const val REJECTED_EVERY = 50
        const val SECONDS_PER_MINUTE = 60L
        const val ONE_SECOND_MILLIS = 1_000L
        val CUSTOMERS = listOf("Kaneza", "Ndayizeye", "Irakoze", "Société Générale")
    }
}
