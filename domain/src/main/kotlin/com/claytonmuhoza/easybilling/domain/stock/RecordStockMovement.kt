package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.DraftLine
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

/**
 * A stock movement as typed, not checked yet. A comma is a decimal point.
 *
 * @property itemId the item that moves.
 * @property type the type chosen, or null when none is.
 * @property quantity the quantity.
 * @property unitCost the unit purchase price, taxes included; only read for an entry.
 * @property description what the user writes of the movement.
 */
data class StockMovementForm(
    val itemId: ItemId,
    val type: MovementType?,
    val quantity: String = "",
    val unitCost: String = "",
    val description: String = "",
)

/** A broken rule of what was typed for a movement. */
enum class MovementProblem {
    /** No type is chosen, or the type is not one recorded by hand. */
    TYPE_MISSING,

    /** The quantity is not a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most. */
    QUANTITY_INVALID,

    /** The unit purchase price of an entry is not a number above zero with two decimals at most. */
    COST_INVALID,

    /** An exit has no description (UC-F07). */
    DESCRIPTION_REQUIRED,

    /** With the reference before it, the description is longer than the OBR accepts. */
    DESCRIPTION_TOO_LONG,
}

/** Why an item cannot move now. */
enum class StockProblem {
    /** There is no such item. */
    ITEM_NOT_FOUND,

    /** The item is not kept in stock: it has no movement. */
    ITEM_NOT_TRACKED,

    /** The item is archived. */
    ITEM_ARCHIVED,
}

/**
 * Gives the unit sale price of an item, taxes included: `item_cost_price` of an exit (D17). It is
 * the total of a sale of one unit today, by the formula of the OBR.
 *
 * @param priceSale computes a sale with the prices and the rates of today.
 * @param time source of the current time.
 */
class UnitSalePrice(private val priceSale: PriceSale, private val time: TimeProvider) {
    /**
     * @param item an item.
     * @return its unit sale price with taxes; its list price when the taxes cannot be computed.
     */
    suspend fun of(item: Item): Money {
        val one = SaleDraft(DraftId(0), lines = listOf(DraftLine(item.id, Quantity.of("1"))), updatedAt = time.now())
        return priceSale(one).lines.firstOrNull()?.amounts?.total ?: item.details.price
    }
}

/**
 * Where the record of a movement reads and writes.
 *
 * @property items the catalogue.
 * @property stock the movements.
 * @property numbering where the code of this device is kept.
 * @property ebmsSettings where the active EBMS environment is kept.
 * @property outbox the send queue.
 * @property auditLog log that records each movement.
 */
class StockStorage(
    val items: ItemRepository,
    val stock: StockRepository,
    val numbering: NumberingRepository,
    val ebmsSettings: EbmsSettingsRepository,
    val outbox: OutboxRepository,
    val auditLog: AuditLog,
)

/**
 * Records an entry or an exit of stock typed by a user (UC-F01, F02, F05, F07): the movement, its
 * entry in the send queue and the audit entry are written together or not at all. An exit never
 * takes more than the stock, and an exit that is not a sale is validated by a manager.
 *
 * @param authority tells who may record a movement, alone or with the approval of a manager.
 * @param storage where the movement is read and written.
 * @param exitPrice gives the unit sale price declared for an exit.
 * @param transactions makes the record a single write.
 * @param time source of the date of the movement.
 */
class RecordStockMovement(
    private val authority: ApprovalAuthority,
    private val storage: StockStorage,
    private val exitPrice: UnitSalePrice,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a record. */
    sealed interface Result {
        /** [movement] is recorded, waiting to be declared to the OBR. */
        data class Recorded(val movement: StockMovement) : Result

        /** What was typed breaks rules; nothing was recorded. */
        data class Invalid(val problems: Set<MovementProblem>) : Result

        /** The item cannot move; nothing was recorded. */
        data class Refused(val problem: StockProblem) : Result

        /** The exit takes more than the [available] stock; nothing was recorded. */
        data class InsufficientStock(val available: BigDecimal) : Result
    }

    /**
     * @param form the movement as typed.
     * @param actor the user who records it.
     * @param approver the manager who validated it with their PIN, or null when [actor] needs none.
     * @return the movement, or why nothing was recorded.
     * @throws PermissionDeniedException if [actor] may not record a movement, or if the movement
     * needs a validation that neither [actor] nor [approver] may give.
     */
    suspend operator fun invoke(form: StockMovementForm, actor: UserId, approver: UserId? = null): Result {
        val validated = form.type in MovementType.VALIDATED_BY_MANAGER
        authority.require(
            actor = actor,
            approver = approver.takeIf { validated },
            alone = if (validated) Permission.MANAGE_STOCK else Permission.RECORD_STOCK,
            withApproval = Permission.RECORD_STOCK,
        )
        return transactions.run {
            when (val examined = examined(form)) {
                is Examined.Stopped -> examined.result
                is Examined.Valid -> stored(examined, actor, approver.takeIf { validated })
            }
        }
    }

    /**
     * Checks a movement without writing anything, before the approval of a manager is asked for.
     *
     * @param form the movement as typed.
     * @return why [form] would not be recorded now, or null when it would be.
     */
    suspend fun problemOf(form: StockMovementForm): Result? = (examined(form) as? Examined.Stopped)?.result

    /** A movement once read and checked. */
    private sealed interface Examined {
        /** Nothing is recorded, for [result]. */
        class Stopped(val result: Result) : Examined

        /** The movement breaks no rule: what is left to write. */
        class Valid(
            val item: Item,
            val type: MovementType,
            val quantity: Quantity,
            val unitCost: Money,
            val reference: String,
            val description: String,
        ) : Examined
    }

    private suspend fun examined(form: StockMovementForm): Examined {
        val item = storage.items.find(form.itemId)
        val problem = when {
            item == null -> StockProblem.ITEM_NOT_FOUND
            !item.details.stockTracked -> StockProblem.ITEM_NOT_TRACKED
            item.archived -> StockProblem.ITEM_ARCHIVED
            else -> null
        }
        return if (item == null || problem != null) {
            Examined.Stopped(Result.Refused(problem ?: StockProblem.ITEM_NOT_FOUND))
        } else {
            checked(item, form)
        }
    }

    private suspend fun checked(item: Item, form: StockMovementForm): Examined {
        val reference = MovementReference.of(deviceCode(), storage.stock.count() + 1)
        val type = form.type?.takeIf { it in MovementType.RECORDED_BY_HAND }
        val entry = type?.direction == MovementDirection.ENTRY
        val quantity = number(form.quantity)
        val cost = number(form.unitCost)
        val description = form.description.trim()
        val problems = setOfNotNull(
            MovementProblem.TYPE_MISSING.takeIf { type == null },
            MovementProblem.QUANTITY_INVALID.takeIf { quantity == null },
            MovementProblem.COST_INVALID.takeIf { entry && cost == null },
            MovementProblem.DESCRIPTION_REQUIRED.takeIf { type != null && !entry && description.isEmpty() },
            MovementProblem.DESCRIPTION_TOO_LONG
                .takeIf { description.length > StockMovementContent.maxTypedLength(reference) },
        )
        val available = storage.stock.quantityOf(item.id)
        return when {
            type == null || quantity == null || problems.isNotEmpty() -> Examined.Stopped(Result.Invalid(problems))
            !entry && quantity > available -> Examined.Stopped(Result.InsufficientStock(available))
            else -> {
                val unitCost = if (entry) Money.of(checkNotNull(cost), Currency.BIF) else exitPrice.of(item)
                Examined.Valid(item, type, Quantity.of(quantity), unitCost, reference, description)
            }
        }
    }

    private suspend fun stored(valid: Examined.Valid, actor: UserId, approver: UserId?): Result {
        // The OBR reads the date to the second.
        val now = time.now().truncatedTo(ChronoUnit.SECONDS)
        val movement = storage.stock.record(
            StockMovementContent(
                reference = valid.reference,
                itemId = valid.item.id,
                itemCode = valid.item.code,
                designation = valid.item.details.designation,
                unit = valid.item.details.unit,
                type = valid.type,
                quantity = valid.quantity,
                unitCost = valid.unitCost,
                description = valid.description,
                occurredAt = now,
                environment = storage.ebmsSettings.read().activeEnvironment,
                recordedBy = actor,
                approvedBy = approver,
            ),
        )
        storage.outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), now)
        storage.auditLog.record(AuditEvent.StockMovementRecorded(actor, movement.id, valid.reference, approver))
        return Result.Recorded(movement)
    }

    /** The code of this device when several devices bill, or an empty text. */
    private suspend fun deviceCode(): String =
        storage.numbering.settings()?.takeIf { it.multipleDevices }?.deviceCode.orEmpty()

    /** @return [text] as a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most, or null. */
    private fun number(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.takeIf { it.signum() > 0 && it.scale() <= SaleLineForm.MAX_DECIMALS }
}
