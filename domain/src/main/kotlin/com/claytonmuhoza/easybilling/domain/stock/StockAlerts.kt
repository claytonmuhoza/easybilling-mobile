package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import java.math.BigDecimal

/** Storage of what is set for the stock of each item, apart from the catalogue. */
interface StockSettingsRepository {
    /** @return the reorder threshold of every item that has one. */
    suspend fun thresholds(): Map<ItemId, BigDecimal>

    /**
     * @param itemId an item.
     * @param threshold its reorder threshold, or null to take it away.
     */
    suspend fun setThreshold(itemId: ItemId, threshold: BigDecimal?)
}

/**
 * The stock as a whole (UC-F10).
 *
 * @property value the value of the items whose purchase price is known: their stock at their last
 * purchase price (D55).
 * @property unvalued how many items in stock have no known purchase price and are left out of [value].
 * @property toReorder how many items are at their reorder threshold or under it.
 */
data class StockSummary(val value: Money, val unvalued: Int, val toReorder: Int) {
    /** The summary of an empty stock. */
    companion object {
        /** No value, nothing to reorder. */
        val EMPTY = StockSummary(Money.zero(Currency.BIF), unvalued = 0, toReorder = 0)
    }
}

/**
 * Sets the reorder threshold of an item kept in stock (UC-F10): at this stock or under it, the
 * item is told to reorder. The threshold is an internal rule and is never sent to the OBR.
 *
 * @param access checks the rights of the user.
 * @param items the catalogue.
 * @param settings where the thresholds are kept.
 * @param auditLog log that records each change.
 */
class SetReorderThreshold(
    private val access: AccessControl,
    private val items: ItemRepository,
    private val settings: StockSettingsRepository,
    private val auditLog: AuditLog,
) {
    /** Outcome of a change. */
    sealed interface Result {
        /** The item now has [threshold], or none when it is null. */
        data class Saved(val threshold: BigDecimal?) : Result

        /** What was typed is not a number of zero or more with [SaleLineForm.MAX_DECIMALS] decimals at most. */
        data object Invalid : Result

        /** The item cannot have a threshold. */
        data class Refused(val problem: StockProblem) : Result
    }

    /**
     * @param itemId the item.
     * @param threshold the threshold as typed; empty to take it away.
     * @param actor the user who sets it.
     * @return the threshold kept, or why nothing changed.
     * @throws PermissionDeniedException if [actor] may not record a stock movement.
     */
    suspend operator fun invoke(itemId: ItemId, threshold: String, actor: UserId): Result {
        access.require(actor, Permission.RECORD_STOCK)
        val item = items.find(itemId)
        val number = threshold.toStockNumber()
        return when {
            item == null -> Result.Refused(StockProblem.ITEM_NOT_FOUND)
            !item.details.stockTracked -> Result.Refused(StockProblem.ITEM_NOT_TRACKED)
            threshold.isNotBlank() && number == null -> Result.Invalid
            else -> {
                settings.setThreshold(itemId, number)
                auditLog.record(AuditEvent.ReorderThresholdSet(actor, itemId, number?.toPlainString()))
                Result.Saved(number)
            }
        }
    }
}
