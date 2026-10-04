package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Where the transmission stands (UC-D03): what waits, since when, what was refused, what was sent. */
class TransmissionStatusTest {

    private val fixture = SaleFixture()
    private val read = ReadTransmissionStatus(fixture.outbox, fixture.invoices, fixture.time)

    private suspend fun issued(): Invoice {
        if (fixture.profiles.current() == null) fixture.ready()
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        fixture.time.advance(Duration.ofSeconds(1))
        val draft = fixture.editSale.addItem(soap.id)
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
    }

    /** The OBR settles [invoice]: it takes [status] and leaves the queue. */
    private suspend fun settle(invoice: Invoice, status: InvoiceStatus) {
        fixture.invoices.setStatus(invoice.id, status, fixture.time.now())
        val entry = fixture.outbox.pending().first { it.operation == OutboxOperation.SendInvoice(invoice.id) }
        fixture.outbox.remove(entry.id)
    }

    @Test
    fun `nothing to send is quiet`() = runTest {
        val status = read()

        assertEquals(emptyList(), status.queue)
        assertEquals(emptyList(), status.rejected)
        assertEquals(0, status.sentToday)
        assertNull(status.oldestWait)
        assertNull(status.nextAttemptAt)
        assertEquals(TransmissionLevel.OK, status.level)
    }

    @Test
    fun `the queue is listed in the order it will be sent, with each invoice`() = runTest {
        val first = issued()
        val second = issued()

        val status = read()

        assertEquals(listOf(first, second), status.queue.map { it.invoice })
        assertEquals(Duration.ofSeconds(1), status.oldestWait)
        assertEquals(fixture.time.now(), status.readAt)
    }

    @Test
    fun `waiting an hour is a warning, waiting a day is an alert`() = runTest {
        issued()

        fixture.time.advance(Duration.ofMinutes(59))
        assertEquals(TransmissionLevel.OK, read().level)

        fixture.time.advance(Duration.ofMinutes(1))
        assertEquals(TransmissionLevel.WARNING, read().level)

        fixture.time.advance(Duration.ofHours(22).plusMinutes(59))
        assertEquals(TransmissionLevel.WARNING, read().level)

        fixture.time.advance(Duration.ofMinutes(1))
        assertEquals(TransmissionLevel.ALERT, read().level)
        assertEquals(Duration.ofHours(24), read().oldestWait)
    }

    @Test
    fun `a refused invoice is an alert even when nothing waits, with the message of the OBR`() = runTest {
        val refusal = "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide"
        val first = issued()
        val second = issued()
        settle(first, InvoiceStatus.Rejected(refusal))
        fixture.time.advance(Duration.ofMinutes(1))
        settle(second, InvoiceStatus.Rejected("Autre motif"))

        val status = read()

        assertEquals(emptyList(), status.queue)
        // The most recently refused first.
        assertEquals(listOf(RejectedDocument(second, "Autre motif"), RejectedDocument(first, refusal)), status.rejected)
        assertEquals(TransmissionLevel.ALERT, status.level)
    }

    @Test
    fun `the next attempt is the one of the first document of the queue`() = runTest {
        issued()
        issued()
        val first = fixture.outbox.pending().first()
        val due = fixture.time.now() + Duration.ofMinutes(2)
        fixture.outbox.update(first.copy(attempts = 3, nextAttemptAt = due, lastError = "ConnectException"))

        val status = read()

        assertEquals(due, status.nextAttemptAt)
        assertEquals(3, status.queue.first().entry.attempts)
        assertEquals("ConnectException", status.queue.first().entry.lastError)
    }

    @Test
    fun `invoices acknowledged today are counted from midnight in Bujumbura`() = runTest {
        // 21:30 UTC is 23:30 in Bujumbura: half an hour before the next day there.
        fixture.time.instant = Instant.parse("2026-10-03T21:30:00Z")
        val yesterday = issued()
        settle(yesterday, InvoiceStatus.Registered)
        assertEquals(1, read().sentToday)

        fixture.time.advance(Duration.ofMinutes(45))
        assertEquals(0, read().sentToday)

        val today = issued()
        settle(today, InvoiceStatus.Registered)
        assertEquals(1, read().sentToday)
    }
}
