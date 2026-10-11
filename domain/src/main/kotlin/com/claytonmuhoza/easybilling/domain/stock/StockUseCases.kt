package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.math.BigDecimal

/**
 * An item kept in stock, with its stock.
 *
 * @property item the item.
 * @property quantity its stock: the sum of its movements.
 */
data class StockLine(val item: Item, val quantity: BigDecimal)

/**
 * Reads the stock of the items kept in stock (UC-F10).
 *
 * @param items the catalogue.
 * @param stock the movements.
 */
class ReadStock(private val items: ItemRepository, private val stock: StockRepository) {
    /**
     * @param query a part of the code, of the designation or of the barcode; empty for every item.
     * @param limit the most items of the catalogue to look through.
     * @return the items kept in stock that match, with their stock, in the order of the catalogue.
     */
    suspend operator fun invoke(query: String, limit: Int): List<StockLine> {
        val quantities = stock.quantities()
        return items.search(query, archived = false, limit = limit)
            .filter { it.details.stockTracked }
            .map { StockLine(it, quantities[it.id] ?: BigDecimal.ZERO) }
    }
}

/**
 * What a manager decides of a movement the OBR refused, or may or may not have (§ 7.8): send it
 * again, or hold an uncertain one as declared. Nothing of this happens by itself.
 *
 * @param access checks the rights of the user.
 * @param stock the movements.
 * @param outbox the send queue.
 * @param auditLog log that records each decision.
 * @param transactions makes a decision a single write.
 * @param time source of the time of the decision.
 */
class ResolveStockMovement(
    private val access: AccessControl,
    private val stock: StockRepository,
    private val outbox: OutboxRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a decision. */
    enum class Result {
        /** The decision is applied. */
        DONE,

        /** The movement does not exist, or its status does not call for this decision. */
        NOT_APPLICABLE,
    }

    /**
     * Puts a refused or uncertain movement back in the send queue. Sending an uncertain movement
     * again may declare it twice: the OBR cannot tell.
     *
     * @param id the movement.
     * @param actor the manager who decides.
     * @return whether the movement waits in the queue again.
     * @throws PermissionDeniedException if [actor] is not allowed to.
     */
    suspend fun resend(id: MovementId, actor: UserId): Result = decide(id, actor, MovementStatus.Pending) {
        outbox.enqueue(OutboxOperation.SendStockMovement(id), time.now())
        auditLog.record(AuditEvent.StockMovementResolved(actor, id, resent = true))
    }

    /**
     * Holds an uncertain movement as declared: nothing is sent again.
     *
     * @param id the movement.
     * @param actor the manager who decides.
     * @return whether the movement is now held as declared.
     * @throws PermissionDeniedException if [actor] is not allowed to.
     */
    suspend fun holdAsDeclared(id: MovementId, actor: UserId): Result = decide(id, actor, MovementStatus.Declared) {
        auditLog.record(AuditEvent.StockMovementResolved(actor, id, resent = false))
    }

    private suspend fun decide(
        id: MovementId,
        actor: UserId,
        next: MovementStatus,
        then: suspend () -> Unit,
    ): Result {
        access.require(actor, Permission.MANAGE_STOCK)
        return transactions.run {
            val status = stock.statusOf(id)
            if (status == null || !status.toResolve || !status.canBecome(next)) {
                Result.NOT_APPLICABLE
            } else {
                stock.setStatus(id, next, time.now())
                then()
                Result.DONE
            }
        }
    }
}
