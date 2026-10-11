package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What was counted of an item during the inventory in progress (UC-F08).
 *
 * @property itemId the item counted.
 * @property counted the quantity found, zero included.
 * @property unitCost the unit purchase price typed for what is found in excess, taxes included, or
 * null: the last purchase price of the item is then declared.
 */
data class InventoryCount(val itemId: ItemId, val counted: BigDecimal, val unitCost: Money? = null)

/** Storage of the inventory in progress: there is one at most, kept until it is closed or given up. */
interface InventoryRepository {
    /** @return what was counted so far, in the order of the items. */
    suspend fun counts(): List<InventoryCount>

    /** @param count what was counted of an item; it replaces what was counted of it before. */
    suspend fun save(count: InventoryCount)

    /** @param itemId an item that is no longer counted. */
    suspend fun remove(itemId: ItemId)

    /** Forgets everything that was counted. */
    suspend fun clear()
}

/**
 * An item of the inventory: what the stock says and what was counted.
 *
 * @property item the item.
 * @property expected its stock: the sum of its movements.
 * @property count what was counted of it, or null while it is not counted.
 * @property lastCost its last purchase price, or null when it never entered the stock with one.
 */
data class InventoryLine(val item: Item, val expected: BigDecimal, val count: InventoryCount?, val lastCost: Money?) {
    /** What was counted more (above zero) or less (below zero) than the stock, or null while not counted. */
    val gap: BigDecimal? get() = count?.let { it.counted - expected }

    /** The unit purchase price declared for what is found in excess: the one typed, or the last one. */
    val entryCost: Money? get() = count?.unitCost ?: lastCost

    /** Whether something is found in excess and no purchase price is known for it. */
    val missingCost: Boolean get() = (gap?.signum() ?: 0) > 0 && entryCost == null
}

/**
 * Reads the inventory in progress (UC-F08).
 *
 * @param items the catalogue.
 * @param stock the movements.
 * @param inventory what was counted.
 */
class ReadInventory(
    private val items: ItemRepository,
    private val stock: StockRepository,
    private val inventory: InventoryRepository,
) {
    /**
     * @param query a part of the code, of the designation or of the barcode; empty for every item.
     * @param limit the most items of the catalogue to look through.
     * @return the items kept in stock that match, with their stock and what was counted of them.
     */
    suspend operator fun invoke(query: String, limit: Int): List<InventoryLine> {
        val quantities = stock.quantities()
        val costs = stock.lastEntryCosts()
        val counts = inventory.counts().associateBy { it.itemId }
        return items.search(query, archived = false, limit = limit)
            .filter { it.details.stockTracked }
            .map { InventoryLine(it, quantities[it.id] ?: BigDecimal.ZERO, counts[it.id], costs[it.id]) }
    }
}

/** A broken rule of what was typed for a count. */
enum class CountProblem {
    /** The quantity is not a number, is below zero or has more than [SaleLineForm.MAX_DECIMALS] decimals. */
    QUANTITY_INVALID,

    /** The unit purchase price is not a number above zero with two decimals at most. */
    COST_INVALID,
}

/**
 * Keeps what is counted during an inventory (UC-F08). Nothing moves the stock before the inventory
 * is closed (see [CloseInventory]).
 *
 * @param access checks the rights of the user.
 * @param items the catalogue.
 * @param inventory what was counted.
 */
class CountInventory(
    private val access: AccessControl,
    private val items: ItemRepository,
    private val inventory: InventoryRepository,
) {
    /** Outcome of a count. */
    sealed interface Result {
        /** [count] is kept. */
        data class Saved(val count: InventoryCount) : Result

        /** Nothing was typed: the item is no longer counted. */
        data object Cleared : Result

        /** What was typed breaks rules; what was counted before is kept. */
        data class Invalid(val problems: Set<CountProblem>) : Result

        /** The item cannot be counted. */
        data class Refused(val problem: StockProblem) : Result
    }

    /**
     * @param itemId the item counted.
     * @param counted the quantity found, as typed; empty when the item is not counted after all.
     * @param unitCost the unit purchase price of what is found in excess, as typed; may be empty.
     * @param actor the user who counts.
     * @return what is kept of the count.
     * @throws PermissionDeniedException if [actor] may not record a stock movement.
     */
    suspend operator fun invoke(itemId: ItemId, counted: String, unitCost: String, actor: UserId): Result {
        access.require(actor, Permission.RECORD_STOCK)
        val item = items.find(itemId)
        val quantity = counted.toStockNumber()
        val cost = unitCost.toStockNumber()?.takeIf { it.signum() > 0 }
        val problems = setOfNotNull(
            CountProblem.QUANTITY_INVALID.takeIf { quantity == null },
            CountProblem.COST_INVALID.takeIf { unitCost.isNotBlank() && cost == null },
        )
        return when {
            item == null -> Result.Refused(StockProblem.ITEM_NOT_FOUND)
            !item.details.stockTracked -> Result.Refused(StockProblem.ITEM_NOT_TRACKED)
            item.archived -> Result.Refused(StockProblem.ITEM_ARCHIVED)
            counted.isBlank() -> Result.Cleared.also { inventory.remove(itemId) }
            quantity == null || problems.isNotEmpty() -> Result.Invalid(problems)
            else -> {
                val count = InventoryCount(itemId, quantity, cost?.let { Money.of(it, Currency.BIF) })
                Result.Saved(count).also { inventory.save(count) }
            }
        }
    }

    /**
     * Gives the inventory in progress up: nothing that was counted is kept, and the stock does not move.
     *
     * @param actor the user who gives it up.
     * @throws PermissionDeniedException if [actor] may not record a stock movement.
     */
    suspend fun discard(actor: UserId) {
        access.require(actor, Permission.RECORD_STOCK)
        inventory.clear()
    }
}

/**
 * @return this text as a quantity of zero or more with [SaleLineForm.MAX_DECIMALS] decimals at most,
 * written without useless zeros, or null. A comma is a decimal point.
 */
internal fun String.toStockNumber(): BigDecimal? = trim().replace(',', '.').toBigDecimalOrNull()
    ?.takeIf { it.signum() >= 0 }
    ?.stripTrailingZeros()
    // "10" without its zeros is 1E+1: it is written 10 again.
    ?.let { if (it.scale() < 0) it.setScale(0) else it }
    ?.takeIf { it.scale() <= SaleLineForm.MAX_DECIMALS }

/**
 * Closes the inventory in progress (UC-F08): each item counted more or less than its stock gets an
 * adjustment, EAJ for an excess and SAJ for a shortage, and the inventory is emptied, together or
 * not at all. The gap is the one of the moment the inventory is closed: a sale made while counting
 * changes it. An adjustment may hide a loss, so a manager closes the inventory, or validates it.
 *
 * @param authority tells who may close an inventory, alone or with the approval of a manager.
 * @param storage where the adjustments are read and written.
 * @param inventory what was counted.
 * @param exitPrice gives the unit sale price declared for a shortage.
 * @param transactions makes the closing a single write.
 * @param time source of the date of the adjustments.
 */
class CloseInventory(
    private val authority: ApprovalAuthority,
    private val storage: StockStorage,
    private val inventory: InventoryRepository,
    private val exitPrice: UnitSalePrice,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a closing. */
    sealed interface Result {
        /** The inventory is closed; [adjustments] were recorded, none when the stock was right. */
        data class Closed(val adjustments: List<StockMovement>) : Result

        /** Nothing was counted; nothing changed. */
        data object NothingCounted : Result

        /** The note is longer than [MAX_NOTE_LENGTH]; nothing changed. */
        data object NoteTooLong : Result

        /** [itemIds] are found in excess and have no purchase price, typed or known; nothing changed. */
        data class MissingCost(val itemIds: Set<ItemId>) : Result
    }

    /** An adjustment to write: the item, its type, its quantity and its unit price. */
    private class Adjustment(val item: Item, val type: MovementType, val quantity: BigDecimal, val unitPrice: Money)

    /** What closing would do. */
    private sealed interface Prepared {
        /** Nothing is written, for [result]. */
        class Stopped(val result: Result) : Prepared

        /** The adjustments to write. */
        class Ready(val adjustments: List<Adjustment>) : Prepared
    }

    /**
     * Checks the inventory without writing anything, before the approval of a manager is asked for.
     *
     * @param note what the user adds to the description of the adjustments.
     * @return why the inventory would not be closed now, or null when it would be.
     */
    suspend fun problemOf(note: String): Result? = (prepared(note) as? Prepared.Stopped)?.result

    /**
     * @param note what the user adds to the description of the adjustments; may be empty.
     * @param actor the user who closes the inventory.
     * @param approver the manager who validated it with their PIN, or null when [actor] needs none.
     * @return the adjustments, or why nothing changed.
     * @throws PermissionDeniedException if neither [actor] nor [approver] may close an inventory.
     */
    suspend operator fun invoke(note: String, actor: UserId, approver: UserId? = null): Result {
        authority.require(actor, approver, alone = Permission.MANAGE_STOCK, withApproval = Permission.RECORD_STOCK)
        return transactions.run {
            when (val prepared = prepared(note)) {
                is Prepared.Stopped -> prepared.result
                is Prepared.Ready -> closed(prepared.adjustments, note.trim(), actor, approver)
            }
        }
    }

    private suspend fun prepared(note: String): Prepared {
        val counts = inventory.counts()
        val quantities = storage.stock.quantities()
        val costs = storage.stock.lastEntryCosts()
        // An item archived or no longer kept in stock since it was counted is left out.
        val lines = counts.mapNotNull { count ->
            storage.items.find(count.itemId)
                ?.takeIf { it.details.stockTracked && !it.archived }
                ?.let { InventoryLine(it, quantities[it.id] ?: BigDecimal.ZERO, count, costs[it.id]) }
        }
        val missing = lines.filter { it.missingCost }.map { it.item.id }.toSet()
        return when {
            counts.isEmpty() -> Prepared.Stopped(Result.NothingCounted)
            note.trim().length > MAX_NOTE_LENGTH -> Prepared.Stopped(Result.NoteTooLong)
            missing.isNotEmpty() -> Prepared.Stopped(Result.MissingCost(missing))
            else -> Prepared.Ready(lines.mapNotNull { adjustment(it) })
        }
    }

    /** @return the adjustment that brings the stock of [line] to what was counted, or null without a gap. */
    private suspend fun adjustment(line: InventoryLine): Adjustment? {
        val gap = line.gap ?: BigDecimal.ZERO
        return when {
            gap.signum() > 0 -> Adjustment(line.item, MovementType.EAJ, gap, checkNotNull(line.entryCost))
            gap.signum() < 0 -> Adjustment(line.item, MovementType.SAJ, gap.negate(), exitPrice.of(line.item))
            else -> null
        }
    }

    private suspend fun closed(adjustments: List<Adjustment>, note: String, actor: UserId, approver: UserId?): Result {
        // The OBR reads the date to the second.
        val now = time.now().truncatedTo(ChronoUnit.SECONDS)
        val title = "Inventaire du ${DATE.format(BusinessCalendar.today(time))}"
        val description = if (note.isEmpty()) title else "$title $note"
        val deviceCode = storage.numbering.settings()?.takeIf { it.multipleDevices }?.deviceCode.orEmpty()
        val environment = storage.ebmsSettings.read().activeEnvironment
        val movements = adjustments.map { adjustment ->
            val reference = MovementReference.of(deviceCode, storage.stock.count() + 1)
            val movement = storage.stock.record(
                StockMovementContent(
                    reference = reference,
                    itemId = adjustment.item.id,
                    itemCode = adjustment.item.code,
                    designation = adjustment.item.details.designation,
                    unit = adjustment.item.details.unit,
                    type = adjustment.type,
                    quantity = Quantity.of(adjustment.quantity),
                    unitCost = adjustment.unitPrice,
                    description = description,
                    occurredAt = now,
                    environment = environment,
                    recordedBy = actor,
                    approvedBy = approver,
                ),
            )
            storage.outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), now)
            storage.auditLog.record(AuditEvent.StockMovementRecorded(actor, movement.id, reference, approver))
            movement
        }
        inventory.clear()
        storage.auditLog.record(AuditEvent.InventoryClosed(actor, movements.size, approver))
        return Result.Closed(movements)
    }

    /** The limits of an inventory. */
    companion object {
        /** Most characters of the note: with the date and the reference, the description stays short. */
        const val MAX_NOTE_LENGTH = 200

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
