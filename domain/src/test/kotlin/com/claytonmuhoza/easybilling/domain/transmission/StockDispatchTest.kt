package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementForm
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.FakeObrGateway
import com.claytonmuhoza.easybilling.domain.testing.FixedTokenProvider
import com.claytonmuhoza.easybilling.domain.testing.InMemoryReceiptArchive
import com.claytonmuhoza.easybilling.domain.testing.StockFixture
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The stock movements in the send queue (UC-F01 to F07, § 7.8): declared in order, tried again
 * while they certainly did not leave, and never sent again by themselves once they may have.
 */
class StockDispatchTest {

    private val fixture = StockFixture()
    private val sale = fixture.sale
    private val gateway = FakeObrGateway()
    private val dispatch = DispatchOutbox(
        DispatchStorage(
            sale.outbox,
            sale.invoices,
            InMemoryReceiptArchive(),
            sale.profiles,
            sale.cancellations,
            sale.stock,
            sale.invoiceStock,
            sale.audit,
            DirectTransactionRunner,
        ),
        DispatchConnection({ sale.ebmsSettings.activeLoginTarget(sale.vault) }, FixedTokenProvider(), gateway),
        ExponentialRetry,
        sale.time,
    )

    /** Records an entry of [quantity] soaps, a second after the previous one. */
    private suspend fun entry(quantity: String = "5"): StockMovement {
        if (sale.profiles.current() == null) sale.ready()
        val soap = sale.items.findByCode("ART-0001") ?: sale.item("ART-0001", "1000", stockTracked = true)
        sale.time.advance(Duration.ofSeconds(1))
        val form = StockMovementForm(soap.id, MovementType.EN, quantity, unitCost = "800")
        return assertIs<RecordStockMovement.Result.Recorded>(fixture.record(form, sale.alice)).movement
    }

    private suspend fun statusOf(movement: StockMovement) = sale.stock.statusOf(movement.id)

    private fun declared() = gateway.calls.filter { it.first == "addStockMovement" }.map { it.third }

    @Test
    fun `the movements are declared in the order they were recorded`() = runTest {
        val first = entry()
        val second = entry()
        sale.audit.events.clear()

        val report = dispatch()

        assertEquals(DispatchReport(declared = 2, remaining = 0), report)
        assertEquals(listOf("000001", "000002"), declared())
        assertEquals(listOf(first, second), gateway.movements)
        assertEquals(MovementStatus.Declared, statusOf(first))
        assertEquals(AuditEvent.StockMovementSettled(first.id, "DECLARED"), sale.audit.events.first())
        assertEquals("STOCK_MOVEMENT_DECLARED", sale.audit.events.first().action)
    }

    @Test
    fun `a refused movement keeps the message of the OBR and does not stop the next ones`() = runTest {
        val first = entry()
        val second = entry()
        gateway.forcedMovements += MovementOutcome.Rejected("Identifiant système incorrect.")

        val report = dispatch()

        assertEquals(DispatchReport(declared = 1, remaining = 0), report)
        assertEquals(MovementStatus.Rejected("Identifiant système incorrect."), statusOf(first))
        assertEquals(MovementStatus.Declared, statusOf(second))
        assertEquals(listOf(first), sale.stock.toResolve())
    }

    @Test
    fun `a movement that certainly did not leave waits in the queue and is sent at a later pass`() = runTest {
        val movement = entry()
        gateway.forcedMovements += MovementOutcome.NotDelivered("UNREACHABLE")

        assertEquals(DispatchReport(remaining = 1), dispatch())
        assertEquals(MovementStatus.Pending, statusOf(movement))
        assertEquals("UNREACHABLE", sale.outbox.pending().single().lastError)

        sale.time.advance(Duration.ofMinutes(1))
        assertEquals(DispatchReport(declared = 1, remaining = 0), dispatch())
        assertEquals(listOf("000001", "000001"), declared())
    }

    @Test
    fun `a movement whose answer never came is to confirm, and nothing sends it again`() = runTest {
        val movement = entry()
        val next = entry()
        gateway.forcedMovements += MovementOutcome.Uncertain("TIMEOUT")
        sale.audit.events.clear()

        val report = dispatch()

        // It leaves the queue without stopping the next one.
        assertEquals(DispatchReport(declared = 1, remaining = 0), report)
        assertEquals(MovementStatus.ToConfirm, statusOf(movement))
        assertEquals(MovementStatus.Declared, statusOf(next))
        assertEquals(AuditEvent.StockMovementSettled(movement.id, "TO_CONFIRM"), sale.audit.events.first())
        sale.time.advance(Duration.ofHours(2))
        dispatch(force = true)
        assertEquals(listOf("000001", "000002"), declared())
        assertEquals(listOf(movement), sale.stock.toResolve())
    }

    @Test
    fun `an uncertain movement is declared once more only when a manager sends it again`() = runTest {
        val movement = entry()
        gateway.forcedMovements += MovementOutcome.Uncertain("TIMEOUT")
        dispatch()

        fixture.resolve.resend(movement.id, sale.alice)
        dispatch()

        assertEquals(listOf("000001", "000001"), declared())
        assertEquals(MovementStatus.Declared, statusOf(movement))
    }

    @Test
    fun `a movement held as declared while it waited in the queue is not sent`() = runTest {
        val movement = entry()
        sale.stock.setStatus(movement.id, MovementStatus.ToConfirm, sale.time.now())
        fixture.resolve.holdAsDeclared(movement.id, sale.alice)

        assertEquals(DispatchReport(remaining = 0), dispatch())
        assertEquals(emptyList(), declared())
    }

    @Test
    fun `the state of the transmission lists the movements to send and those a manager decides`() = runTest {
        val waiting = entry()
        val uncertain = entry()
        sale.stock.setStatus(uncertain.id, MovementStatus.ToConfirm, sale.time.now())
        val read = ReadTransmissionStatus(sale.outbox, sale.invoices, sale.stock, sale.time)

        val status = read()

        assertEquals(listOf(waiting, uncertain), status.queue.map { it.movement })
        assertEquals(listOf(null, null), status.queue.map { it.invoice })
        assertEquals(listOf(MovementToResolve(uncertain, MovementStatus.ToConfirm)), status.stockToResolve)
        assertEquals(TransmissionLevel.ALERT, status.level)
    }
}
