package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * The stock movements that come with the invoices (UC-F06, F09, E01, E06, D53): the exit of a
 * sale, the entry of goods brought back and of a cancelled invoice, and what happens to them when
 * the OBR refuses their document. Only the lines of items kept in stock move it.
 *
 * Every function writes with the document it is called for: the caller holds the transaction.
 *
 * @param stock the movements.
 * @param numbering where the code of this device is kept.
 * @param outbox the send queue.
 * @param auditLog log that records each movement.
 */
class InvoiceStock(
    private val stock: StockRepository,
    private val numbering: NumberingRepository,
    private val outbox: OutboxRepository,
    private val auditLog: AuditLog,
) {
    /**
     * @param itemId an item kept in stock.
     * @param replaced the invoice the sale replaces, or null: what it took comes back when the OBR
     * cancels it, so the sale that replaces it may sell it again.
     * @return what a sale may take of the item now.
     */
    suspend fun available(itemId: ItemId, replaced: InvoiceId?): BigDecimal {
        val takenByReplaced = replaced?.let { exitsOf(it) }.orEmpty()
            .filter { it.content.itemId == itemId }
            .sumOf { it.content.quantity.value }
        return stock.quantityOf(itemId) + takenByReplaced
    }

    /**
     * Takes the items of a sale out of the stock (UC-F06): one exit SN per line, declared after
     * the invoice at the unit price paid, taxes included.
     *
     * @param invoice the invoice just issued, already in the send queue.
     */
    suspend fun exits(invoice: Invoice) {
        val content = invoice.content
        content.lines.filter { it.stockTracked }.forEach { line ->
            record(
                invoice,
                Moved(MovementType.SN, line, line.quantity, line.unitPricePaid(), "Facture ${content.number}"),
                invoiceRef = null,
                actor = content.issuedBy,
                at = content.issuedAt,
            )
        }
    }

    /**
     * Puts goods brought back into the stock (UC-E06): one entry ER per line of the credit note,
     * declared after it with the number of the invoice, at the price they left the stock at.
     *
     * @param creditNote the credit note just issued, already in the send queue.
     * @param origin the invoice it credits.
     */
    suspend fun returns(creditNote: Invoice, origin: Invoice) {
        val content = creditNote.content
        val exits = exitsOf(origin.id)
        content.lines.filter { it.stockTracked }.forEach { line ->
            val price = exits.firstOrNull { it.content.itemId == line.itemId }?.content?.unitCost
                ?: line.unitPricePaid()
            val description = "Retour marchandises, avoir ${content.number}"
            record(
                creditNote,
                Moved(MovementType.ER, line, line.quantity, price, description),
                invoiceRef = origin.content.number,
                actor = content.issuedBy,
                at = content.issuedAt,
            )
        }
    }

    /**
     * Puts the items of a cancelled invoice back into the stock (UC-E01): one entry ER per exit
     * the invoice made, with its number.
     *
     * @param invoice the invoice the OBR just cancelled.
     * @param actor the user who asked for the cancellation.
     * @param at when the OBR cancelled it.
     */
    suspend fun restore(invoice: Invoice, actor: UserId, at: Instant) {
        val lines = invoice.content.lines.associateBy { it.itemId }
        exitsOf(invoice.id).forEach { exit ->
            val line = lines[exit.content.itemId] ?: return@forEach
            val description = "Annulation de la facture ${invoice.content.number}"
            record(
                invoice,
                Moved(MovementType.ER, line, exit.content.quantity, exit.content.unitCost, description),
                invoiceRef = invoice.content.number,
                actor = actor,
                at = at,
            )
        }
    }

    /**
     * Voids the movements of a document the OBR refused: they were not declared, will not be, and
     * no longer count in the stock.
     *
     * @param invoice the refused invoice or credit note.
     * @param at when it was refused.
     */
    suspend fun void(invoice: InvoiceId, at: Instant) {
        stock.ofInvoice(invoice).filter { stock.statusOf(it.id) == MovementStatus.Pending }.forEach { movement ->
            stock.setStatus(movement.id, MovementStatus.Voided, at)
            auditLog.record(AuditEvent.StockMovementSettled(movement.id, MovementStatus.Voided.code))
        }
    }

    /**
     * @param invoice a refused invoice or credit note.
     * @return the items its voided exits would take more of than the stock has now; empty when it
     * may be sent again.
     */
    suspend fun missingToRevive(invoice: InvoiceId): Set<ItemId> = voidedOf(invoice)
        .filter { it.content.type.direction == MovementDirection.EXIT }
        .groupBy { it.content.itemId }
        .filter { (itemId, exits) -> exits.sumOf { it.content.quantity.value } > stock.quantityOf(itemId) }
        .keys

    /**
     * Brings the voided movements of a refused document back, behind it in the send queue: it is
     * sent again.
     *
     * @param invoice the invoice or credit note sent again, already in the send queue.
     * @param at when it was sent again.
     */
    suspend fun revive(invoice: InvoiceId, at: Instant) {
        voidedOf(invoice).forEach { movement ->
            stock.setStatus(movement.id, MovementStatus.Pending, at)
            outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), at)
        }
    }

    private suspend fun voidedOf(invoice: InvoiceId): List<StockMovement> =
        stock.ofInvoice(invoice).filter { stock.statusOf(it.id) == MovementStatus.Voided }

    /** The exits of a sale that still count: the ones of an invoice the OBR refused do not. */
    private suspend fun exitsOf(invoice: InvoiceId): List<StockMovement> = stock.ofInvoice(invoice)
        .filter { it.content.type == MovementType.SN && stock.statusOf(it.id)?.counts == true }

    /** What moves: the line it comes from, how much of it, at which unit price, and why. */
    private class Moved(
        val type: MovementType,
        val line: InvoiceLine,
        val quantity: Quantity,
        val unitPrice: Money,
        val description: String,
    )

    private suspend fun record(invoice: Invoice, moved: Moved, invoiceRef: String?, actor: UserId, at: Instant) {
        val reference = MovementReference.of(deviceCode(), stock.count() + 1)
        val movement = stock.record(
            StockMovementContent(
                reference = reference,
                itemId = moved.line.itemId,
                itemCode = moved.line.itemCode,
                designation = moved.line.designation,
                unit = moved.line.unit,
                type = moved.type,
                quantity = moved.quantity,
                unitCost = moved.unitPrice,
                description = moved.description,
                invoiceRef = invoiceRef,
                occurredAt = at,
                environment = invoice.content.environment,
                recordedBy = actor,
                invoiceId = invoice.id,
            ),
        )
        outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), at)
        auditLog.record(AuditEvent.StockMovementRecorded(actor, movement.id, reference, approver = null))
    }

    /** The code of this device when several devices bill, or an empty text. */
    private suspend fun deviceCode(): String =
        numbering.settings()?.takeIf { it.multipleDevices }?.deviceCode.orEmpty()

    private companion object {
        /** Decimals kept while a total is divided, before the rounding of the OBR. */
        const val DIVISION_SCALE = 10

        /** @return the unit price paid for the line, taxes included: its total over its quantity (D54). */
        fun InvoiceLine.unitPricePaid(): Money {
            val unit = amounts.total.amount.divide(quantity.value, DIVISION_SCALE, RoundingMode.HALF_UP)
            return Money.of(ObrRoundingPolicy.round(unit), amounts.total.currency)
        }
    }
}
