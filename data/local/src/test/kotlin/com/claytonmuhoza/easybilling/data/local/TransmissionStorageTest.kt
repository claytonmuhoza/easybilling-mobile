package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The state of the send queue and the acknowledgements of the OBR, on a real database file. */
@RunWith(AndroidJUnit4::class)
class TransmissionStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name: see RoomItemRepositoryTest.
    private val name = "sending.db"
    private var storage = LocalStorage.unencryptedFile(context, name)
    private val now = Instant.parse("2026-10-03T08:15:42.123Z")

    private val receipt = ObrReceipt(
        registeredNumber = "4530253",
        registeredDate = "2026-10-03 10:04:23",
        signature = "9a2a3f27dd833f6f4a069235ce165723be1b7340",
        rawResponse = """{"success":true,"msg":"La facture a été ajoutée avec succès!"}""",
    )

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun content(number: String, environment: EbmsEnvironment = EbmsEnvironment.TEST) = InvoiceContent(
        type = DocumentType.FN,
        number = number,
        identifier = "4000000001/ws400000000100001/20261003101542/$number",
        issuedAt = now,
        profileVersion = 1,
        environment = environment,
        customer = InvoiceCustomer(CustomerId(1), "Client comptoir", null, "", false),
        paymentType = PaymentType.CASH,
        currency = Currency.BIF,
        lines = listOf(
            InvoiceLine(
                itemId = ItemId(1),
                itemCode = "ART-0001",
                designation = "Savon de Marseille",
                unit = "pièce",
                stockTracked = true,
                quantity = Quantity.of("1"),
                listUnitPrice = bif("1000"),
                priceMode = PriceMode.EXCLUDING_TAXES,
                discount = null,
                vatRate = null,
                amounts = LineAmounts(
                    bif("1000"), bif("1000"), bif("0"), bif("0"), bif("0"),
                    bif("1000"), bif("0"), bif("1000"), bif("0"), bif("1000"),
                ),
            ),
        ),
        issuedBy = UserId(1),
    )

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `an operation enters the queue ready to be tried, and keeps what each attempt left`() = runTest {
        val first = storage.outbox.enqueue(OutboxOperation.SendInvoice(InvoiceId(7)), now)
        val second = storage.outbox.enqueue(OutboxOperation.SendInvoice(InvoiceId(8)), now.plusSeconds(1))

        assertEquals(listOf(first, second), storage.outbox.pending())
        assertEquals(0, first.attempts)
        assertNull(first.nextAttemptAt)

        val postponed = first.copy(
            attempts = 3,
            nextAttemptAt = now.plusSeconds(120),
            uncertain = true,
            lastError = "SocketTimeoutException: timeout",
        )
        storage.outbox.update(postponed)

        // The application restarts: the queue is as it was left.
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)
        assertEquals(listOf(postponed, second), storage.outbox.pending())
    }

    @Test
    fun `an operation leaves the queue without moving the others`() = runTest {
        val first = storage.outbox.enqueue(OutboxOperation.SendInvoice(InvoiceId(7)), now)
        val second = storage.outbox.enqueue(OutboxOperation.SendInvoice(InvoiceId(8)), now)

        storage.outbox.remove(first.id)

        assertEquals(listOf(second), storage.outbox.pending())
        val third = storage.outbox.enqueue(OutboxOperation.SendInvoice(InvoiceId(9)), now)
        assertEquals(listOf(second.id, third.id), storage.outbox.pending().map { it.id })
    }

    @Test
    fun `an acknowledgement is read back as it was received`() = runTest {
        assertNull(storage.receipts.find(InvoiceId(1)))

        storage.receipts.archive(InvoiceId(1), receipt, now)

        assertEquals(receipt, storage.receipts.find(InvoiceId(1)))
        assertNull(storage.receipts.find(InvoiceId(2)))
    }

    @Test
    fun `the database itself refuses a second acknowledgement and any change to the first`() = runTest {
        storage.receipts.archive(InvoiceId(1), receipt, now)

        assertFailsWith<SQLiteConstraintException> {
            storage.receipts.archive(InvoiceId(1), receipt.copy(registeredNumber = "other"), now)
        }
        val database = storage.database.openHelper.writableDatabase
        for (statement in listOf("UPDATE invoice_receipts SET signature = 'forged'", "DELETE FROM invoice_receipts")) {
            assertFailsWith<Exception>(statement) { database.execSQL(statement) }
        }
        assertEquals(receipt, storage.receipts.find(InvoiceId(1)))
    }

    @Test
    fun `the acknowledgement and the status are written together or not at all`() = runTest {
        val invoice = storage.invoices.create(content("FN-00001/2026"))
        storage.receipts.archive(invoice.id, receipt, now)

        // The second acknowledgement is refused: the status written just before it must not stay.
        assertFailsWith<SQLiteConstraintException> {
            storage.transactions.run {
                storage.invoices.setStatus(
                    invoice.id,
                    InvoiceStatus.Registered,
                    now,
                )
                storage.receipts.archive(invoice.id, receipt, now)
            }
        }

        assertEquals(
            InvoiceStatus.Pending,
            storage.invoices.statusOf(invoice.id),
        )
    }

    @Test
    fun `refused invoices are listed with the message of the OBR, the most recent first`() = runTest {
        val first = storage.invoices.create(content("FN-00001/2026"))
        val second = storage.invoices.create(content("FN-00002/2026"))
        storage.invoices.create(content("FN-00003/2026"))
        storage.invoices.setStatus(first.id, InvoiceStatus.Rejected("Premier motif"), now)
        storage.invoices.setStatus(second.id, InvoiceStatus.Rejected("Second motif"), now.plusSeconds(60))

        assertEquals(
            listOf(RejectedDocument(second, "Second motif"), RejectedDocument(first, "Premier motif")),
            storage.invoices.rejected(),
        )

        // Sent again, an invoice is no longer among the refused ones.
        storage.invoices.setStatus(second.id, InvoiceStatus.Pending, now.plusSeconds(120))
        assertEquals(listOf(first.id), storage.invoices.rejected().map { it.invoice.id })
    }

    @Test
    fun `acknowledged invoices are counted from a moment`() = runTest {
        val early = storage.invoices.create(content("FN-00001/2026"))
        val late = storage.invoices.create(content("FN-00002/2026"))
        storage.invoices.create(content("FN-00003/2026"))
        storage.invoices.setStatus(early.id, InvoiceStatus.Registered, now)
        storage.invoices.setStatus(late.id, InvoiceStatus.Registered, now.plusSeconds(3600))

        assertEquals(2, storage.invoices.countRegisteredSince(now))
        assertEquals(1, storage.invoices.countRegisteredSince(now.plusSeconds(1)))
        assertEquals(0, storage.invoices.countRegisteredSince(now.plusSeconds(3601)))
    }

    @Test
    fun `invoices are counted by the environment they were issued for`() = runTest {
        storage.invoices.create(content("FN-00001/2026"))
        storage.invoices.create(content("FN-00002/2026"))
        storage.invoices.create(content("FN-00003/2026", EbmsEnvironment.PRODUCTION))

        assertEquals(2, storage.invoices.countIn(EbmsEnvironment.TEST))
        assertEquals(1, storage.invoices.countIn(EbmsEnvironment.PRODUCTION))
    }
}
