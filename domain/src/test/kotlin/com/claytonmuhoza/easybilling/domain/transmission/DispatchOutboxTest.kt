package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EnvironmentRefusal
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.FakeObrGateway
import com.claytonmuhoza.easybilling.domain.testing.InMemoryReceiptArchive
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The send queue (UC-C01 steps 6 and 7, UC-D02, D05, D09): order, retries, lost answers, rejections. */
class DispatchOutboxTest {

    private val fixture = SaleFixture()
    private val gateway = FakeObrGateway()
    private val receipts = InMemoryReceiptArchive()
    private var logins = 0
    private var invalidations = 0
    private var login: () -> LoginOutcome = { LoginOutcome.Success("token-${++logins}") }
    private val tokens = object : TokenProvider {
        override suspend fun token(): LoginOutcome = login()

        override fun invalidate() {
            invalidations++
        }
    }
    private val dispatch = DispatchOutbox(
        DispatchStorage(
            fixture.outbox,
            fixture.invoices,
            receipts,
            fixture.profiles,
            fixture.audit,
            DirectTransactionRunner,
        ),
        DispatchConnection({ fixture.ebmsSettings.activeLoginTarget(fixture.vault) }, tokens, gateway),
        ExponentialRetry,
        fixture.time,
    )

    /** Issues [count] invoices of one soap each, a second apart. */
    private suspend fun issued(count: Int = 1): List<Invoice> {
        if (fixture.profiles.current() == null) fixture.ready()
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        return List(count) {
            fixture.time.advance(Duration.ofSeconds(1))
            val draft = fixture.editSale.addItem(soap.id)
            assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
        }
    }

    private suspend fun statusOf(invoice: Invoice) = fixture.invoices.statusOf(invoice.id)

    private fun sent() = gateway.calls.filter { it.first == "addInvoice" }.map { it.third }

    @Test
    fun `an accepted invoice is registered, its acknowledgement archived and the queue emptied`() = runTest {
        val invoice = issued().single()
        fixture.audit.events.clear()

        val report = dispatch()

        assertEquals(DispatchReport(registered = 1, rejected = 0, remaining = 0), report)
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
        assertEquals(gateway.receiptOf(invoice), receipts.find(invoice.id))
        assertEquals(emptyList(), fixture.outbox.pending())
        assertEquals(AuditEvent.InvoiceRegistered(invoice.id, "REG-FN-00001/2026"), fixture.audit.events.single())
        assertEquals("invoice=${invoice.id.value};registered=REG-FN-00001/2026", fixture.audit.events.single().details)
        assertNull(fixture.audit.events.single().actor)
    }

    @Test
    fun `invoices are sent in the order they were issued`() = runTest {
        issued(3)

        assertEquals(DispatchReport(registered = 3, remaining = 0), dispatch())
        assertEquals(listOf("FN-00001/2026", "FN-00002/2026", "FN-00003/2026"), sent())
    }

    @Test
    fun `a rejected invoice keeps the message of the OBR and does not stop the next ones`() = runTest {
        val (first, second) = issued(2)
        val refusal = "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide"
        gateway.forcedSends += SendOutcome.Rejected(refusal)
        fixture.audit.events.clear()

        val report = dispatch()

        assertEquals(DispatchReport(registered = 1, rejected = 1, remaining = 0), report)
        assertEquals(InvoiceStatus.Rejected(refusal), statusOf(first))
        assertEquals(InvoiceStatus.Registered, statusOf(second))
        assertNull(receipts.find(first.id))
        assertEquals(AuditEvent.InvoiceRejected(first.id), fixture.audit.events.first())
    }

    @Test
    fun `an invoice that could not be delivered stops the queue and is tried again later`() = runTest {
        val (first, second) = issued(2)
        gateway.forcedSends += SendOutcome.NotDelivered("ConnectException")
        val start = fixture.time.now()

        assertEquals(DispatchReport(remaining = 2), dispatch())

        assertEquals(InvoiceStatus.Pending, statusOf(first))
        assertEquals(InvoiceStatus.Pending, statusOf(second))
        assertEquals(listOf("FN-00001/2026"), sent())
        val entry = fixture.outbox.pending().first()
        assertEquals(1, entry.attempts)
        assertEquals(start + Duration.ofSeconds(30), entry.nextAttemptAt)
        assertEquals("ConnectException", entry.lastError)

        // Before its time, nothing is sent; once the wait is over, both invoices go, in order.
        fixture.time.advance(Duration.ofSeconds(29))
        assertEquals(DispatchReport(remaining = 2), dispatch())
        assertEquals(listOf("FN-00001/2026"), sent())

        fixture.time.advance(Duration.ofSeconds(1))
        assertEquals(DispatchReport(registered = 2, remaining = 0), dispatch())
        assertEquals(listOf("FN-00001/2026", "FN-00001/2026", "FN-00002/2026"), sent())
    }

    @Test
    fun `the wait doubles from 30 seconds to 30 minutes`() = runTest {
        val seconds = (1..9).map { ExponentialRetry.delay(it).seconds }

        assertEquals(listOf<Long>(30, 60, 120, 240, 480, 960, 1800, 1800, 1800), seconds)
        assertEquals(Duration.ofMinutes(30), ExponentialRetry.delay(1_000))
    }

    @Test
    fun `a forced pass does not wait`() = runTest {
        issued()
        gateway.forcedSends += SendOutcome.NotDelivered("ConnectException")
        dispatch()

        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch(force = true))
    }

    @Test
    fun `after a lost answer the OBR is asked before anything is sent again, and nothing is sent twice`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.Uncertain("SocketTimeoutException")
        gateway.lostAnswerReachesObr = true

        assertEquals(DispatchReport(remaining = 1), dispatch())
        assertEquals(true, fixture.outbox.pending().single().uncertain)
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))

        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch(force = true))

        assertEquals(listOf("addInvoice", "getInvoice"), gateway.calls.map { it.first })
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
        // The answer was lost with its acknowledgement: there is none to archive.
        assertNull(receipts.find(invoice.id))
        assertEquals(AuditEvent.InvoiceRegistered(invoice.id, ""), fixture.audit.events.last())
    }

    @Test
    fun `after a lost request the invoice is sent again once the OBR says it does not have it`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.Uncertain("SocketTimeoutException")

        dispatch()
        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch(force = true))

        assertEquals(listOf("addInvoice", "getInvoice", "addInvoice"), gateway.calls.map { it.first })
        assertEquals(gateway.receiptOf(invoice), receipts.find(invoice.id))
    }

    @Test
    fun `while the OBR cannot say whether it has the invoice, nothing is sent`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.Uncertain("SocketTimeoutException")
        gateway.forcedLookups += LookupOutcome.Unavailable("ConnectException")

        dispatch()
        assertEquals(DispatchReport(remaining = 1), dispatch(force = true))

        assertEquals(listOf("addInvoice", "getInvoice"), gateway.calls.map { it.first })
        assertEquals(true, fixture.outbox.pending().single().uncertain)
        assertEquals(2, fixture.outbox.pending().single().attempts)
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
    }

    @Test
    fun `a number the OBR already has is our invoice when the OBR finds its identifier`() = runTest {
        val invoice = issued().single()
        gateway.known += invoice.content.identifier

        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch())

        assertEquals(listOf("addInvoice", "getInvoice"), gateway.calls.map { it.first })
        assertEquals(invoice.content.identifier, gateway.calls.last().third)
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
    }

    @Test
    fun `a number the OBR already has under another identifier is a rejection`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.Rejected(ObrMessages.DUPLICATE_NUMBER)
        gateway.forcedLookups += LookupOutcome.NotFound

        assertEquals(DispatchReport(rejected = 1, remaining = 0), dispatch())
        assertEquals(InvoiceStatus.Rejected(ObrMessages.DUPLICATE_NUMBER), statusOf(invoice))
    }

    @Test
    fun `a date the OBR finds in the future is sent again later instead of being rejected`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.Rejected(ObrMessages.DATE_IN_FUTURE)

        assertEquals(DispatchReport(remaining = 1), dispatch())

        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        assertEquals(ObrMessages.DATE_IN_FUTURE, fixture.outbox.pending().single().lastError)
        assertEquals(false, fixture.outbox.pending().single().uncertain)
        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch(force = true))
    }

    @Test
    fun `a refused token is renewed and the invoice sent again once`() = runTest {
        val invoice = issued().single()
        gateway.forcedSends += SendOutcome.TokenRefused

        assertEquals(DispatchReport(registered = 1, remaining = 0), dispatch())

        assertEquals(1, invalidations)
        assertEquals(listOf("token-1", "token-2"), gateway.calls.map { it.second })
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
    }

    @Test
    fun `a token refused twice waits for later`() = runTest {
        issued()
        gateway.forcedSends += listOf(SendOutcome.TokenRefused, SendOutcome.TokenRefused)

        assertEquals(DispatchReport(remaining = 1), dispatch())
        assertEquals(2, gateway.calls.size)
        assertEquals("TOKEN_REFUSED", fixture.outbox.pending().single().lastError)
    }

    @Test
    fun `without a token nothing is sent and the queue waits`() = runTest {
        val invoice = issued().single()
        login = { LoginOutcome.Unreachable }

        assertEquals(DispatchReport(remaining = 1), dispatch())

        assertEquals(emptyList(), gateway.calls)
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        assertEquals(1, fixture.outbox.pending().single().attempts)
    }

    @Test
    fun `an empty queue asks nothing`() = runTest {
        assertEquals(DispatchReport(), dispatch())
        assertEquals(0, logins)
    }

    @Test
    fun `the invoice is sent with the tax profile it was issued with`() = runTest {
        val invoice = issued().single()
        // The profile changes after the issue: version 2 is no longer registered for the VAT.
        fixture.saveProfile(TaxRegistrations())

        dispatch()

        assertEquals(1, invoice.content.profileVersion)
        assertEquals(true, gateway.sellers.single().taxes.vat)
    }

    @Test
    fun `going back to test is refused once a real invoice was issued in production`() = runTest {
        val lock = InvoiceEnvironmentLock(fixture.invoices, fixture.outbox)
        val test = issued().single()
        dispatch()
        val real = test.content.copy(
            number = "FN-00001/2027",
            identifier = "${test.content.identifier}-production",
            environment = EbmsEnvironment.PRODUCTION,
        )
        fixture.invoices.create(real)

        assertEquals(
            EnvironmentRefusal.PRODUCTION_IN_USE,
            lock.refusal(EbmsEnvironment.PRODUCTION, EbmsEnvironment.TEST),
        )
        assertNull(lock.refusal(EbmsEnvironment.TEST, EbmsEnvironment.PRODUCTION))
    }

    @Test
    fun `the environment does not change while invoices wait, nor back to test after a real invoice`() = runTest {
        val lock = InvoiceEnvironmentLock(fixture.invoices, fixture.outbox)
        assertNull(lock.refusal(EbmsEnvironment.TEST, EbmsEnvironment.PRODUCTION))

        issued()
        assertEquals(
            EnvironmentRefusal.PENDING_TRANSMISSIONS,
            lock.refusal(EbmsEnvironment.TEST, EbmsEnvironment.PRODUCTION),
        )

        dispatch()
        // The invoice was issued in test: nothing prevents going to production, then coming back.
        assertNull(lock.refusal(EbmsEnvironment.TEST, EbmsEnvironment.PRODUCTION))
        assertNull(lock.refusal(EbmsEnvironment.PRODUCTION, EbmsEnvironment.TEST))
        assertNull(lock.refusal(EbmsEnvironment.TEST, EbmsEnvironment.TEST))
    }
}
