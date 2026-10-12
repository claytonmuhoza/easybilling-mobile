package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import java.time.Instant

/** Identifier of a stock movement in the local storage. */
@JvmInline
value class MovementId(val value: Long)

/** Whether a movement adds to the stock or takes from it. */
enum class MovementDirection {
    /** The stock grows. */
    ENTRY,

    /** The stock shrinks. */
    EXIT,
}

/**
 * The types of stock movement of the OBR (`item_movement_type`, page 14 of the specification).
 * The name of each type is the code sent.
 *
 * @property direction whether the type adds to the stock or takes from it.
 */
enum class MovementType(val direction: MovementDirection) {
    /** Entrée normale: a purchase. */
    EN(MovementDirection.ENTRY),

    /** Entrée retour marchandises: goods a customer brought back. */
    ER(MovementDirection.ENTRY),

    /** Entrée inventaire: the stock counted when the inventory starts. */
    EI(MovementDirection.ENTRY),

    /** Entrée ajustement: an inventory found more than expected. */
    EAJ(MovementDirection.ENTRY),

    /** Entrée transfert. */
    ET(MovementDirection.ENTRY),

    /** Entrée autres. */
    EAU(MovementDirection.ENTRY),

    /** Sortie normale: a sale. */
    SN(MovementDirection.EXIT),

    /** Sortie perte. */
    SP(MovementDirection.EXIT),

    /** Sortie vol. */
    SV(MovementDirection.EXIT),

    /** Sortie désuétude: obsolete or expired goods. */
    SD(MovementDirection.EXIT),

    /** Sortie casse. */
    SC(MovementDirection.EXIT),

    /** Sortie ajustement: an inventory found less than expected. */
    SAJ(MovementDirection.EXIT),

    /** Sortie transfert. */
    ST(MovementDirection.EXIT),

    /** Sortie autres. */
    SAU(MovementDirection.EXIT),
    ;

    /** `item_movement_type`. */
    val code: String get() = name

    /** Which types a user records by hand, and which of them a manager validates. */
    companion object {
        /**
         * The types recorded by hand (UC-F01, F02, F05, F07). The others come with a sale, a credit
         * note or an inventory.
         */
        val RECORDED_BY_HAND: Set<MovementType> = setOf(EI, EN, ET, EAU, SP, SV, SD, SC, ST, SAU)

        /** The exits that are not sales and need the validation of a manager (UC-F07). */
        val VALIDATED_BY_MANAGER: Set<MovementType> = setOf(SP, SV, SD, SC, SAU)
    }
}

/**
 * The unique reference of a movement (`docs/architecture.md` § 7.8): the OBR gives no identifier
 * to a movement, so each one carries its own at the start of its description.
 */
object MovementReference {
    private const val DIGITS = 6

    /**
     * @param deviceCode the code of this device, or an empty text if it has none.
     * @param counter the rank of the movement on this device, from one.
     * @return the reference: `A1-000123`, or `000123` without a device code.
     */
    fun of(deviceCode: String, counter: Long): String {
        val rank = counter.toString().padStart(DIGITS, '0')
        return if (deviceCode.isEmpty()) rank else "$deviceCode-$rank"
    }
}

/**
 * What a stock movement is made of. It copies the item as it was when the movement was recorded
 * and never changes.
 *
 * @property reference the unique reference of the movement on this device.
 * @property itemId the item.
 * @property itemCode `item_code`.
 * @property designation `item_designation`.
 * @property unit `item_measurement_unit`.
 * @property type `item_movement_type`.
 * @property quantity `item_quantity`.
 * @property unitCost `item_cost_price`: the unit purchase price of an entry, the unit sale price
 * of an exit, taxes included (decision D17).
 * @property description what the user wrote; may be empty for an entry.
 * @property invoiceRef `item_movement_invoice_ref`: the invoice of goods brought back, or null.
 * @property occurredAt `item_movement_date`, to the second.
 * @property environment the EBMS environment the movement is declared to.
 * @property recordedBy the user who recorded it.
 * @property approvedBy the manager who validated it with their PIN, or null.
 * @property invoiceId the invoice or the credit note the movement was written with, or null for a
 * movement typed by a user. It never leaves the device.
 * @property imported what the entry of imported goods adds (`AddStockMovementImporters/`), or null
 * for any other movement.
 */
data class StockMovementContent(
    val reference: String,
    val itemId: ItemId,
    val itemCode: String,
    val designation: String,
    val unit: String,
    val type: MovementType,
    val quantity: Quantity,
    val unitCost: Money,
    val description: String,
    val invoiceRef: String? = null,
    val occurredAt: Instant,
    val environment: EbmsEnvironment,
    val recordedBy: UserId,
    val approvedBy: UserId? = null,
    val invoiceId: InvoiceId? = null,
    val imported: ImportedGoods? = null,
) {
    /** `item_movement_description`: the reference, then what the user wrote. */
    val obrDescription: String get() = if (description.isEmpty()) reference else "$reference $description"

    /** What the movement does to the stock: its quantity, negative for an exit. */
    val signedQuantity: BigDecimal
        get() = if (type.direction == MovementDirection.ENTRY) quantity.value else quantity.value.negate()

    /** The limits of a movement. */
    companion object {
        /** Most characters of `item_movement_description` (page 14 of the specification). */
        const val MAX_DESCRIPTION_LENGTH = 500

        /**
         * @param reference the reference of a movement.
         * @return the most characters the user may write for the description sent to hold in
         * [MAX_DESCRIPTION_LENGTH] with [reference] before it.
         */
        fun maxTypedLength(reference: String): Int = MAX_DESCRIPTION_LENGTH - reference.length - 1
    }
}

/**
 * A recorded stock movement.
 *
 * @property id identifier in the local storage.
 * @property content what was recorded.
 */
data class StockMovement(val id: MovementId, val content: StockMovementContent)

/**
 * Where a movement stands with the OBR. A recorded movement counts in the local stock whatever the
 * OBR says of it, since the goods did move (D53); only a voided one does not.
 *
 * @property code stable name of the status, stored with the movement.
 */
sealed class MovementStatus(val code: String) {
    /** Recorded on the device, not sent yet or waiting to be sent again. */
    data object Pending : MovementStatus("PENDING")

    /** Accepted by the OBR. */
    data object Declared : MovementStatus("DECLARED")

    /** Refused by the OBR, with its [message] (`msg`). */
    data class Rejected(val message: String) : MovementStatus("REJECTED")

    /**
     * The request left and no answer came: the OBR may have the movement or not, and cannot be
     * asked. Nothing is sent again by itself: a manager decides (§ 7.8).
     */
    data object ToConfirm : MovementStatus("TO_CONFIRM")

    /**
     * Written with an invoice or a credit note the OBR refused: it was not declared, will not be,
     * and does not count in the stock (D54). It comes back when its document is sent again.
     */
    data object Voided : MovementStatus("VOIDED")

    /** Whether the movement counts in the stock of its item. */
    val counts: Boolean get() = this != Voided

    /** Whether a manager has something to decide: the movement was refused or is uncertain. */
    val toResolve: Boolean get() = this is Rejected || this == ToConfirm

    /**
     * @param next another status.
     * @return whether a movement may go from this status to [next]: a pending one is declared,
     * rejected, uncertain or voided; a rejected, uncertain or voided one is sent again; an
     * uncertain one may be held as declared. A declared movement never changes.
     */
    fun canBecome(next: MovementStatus): Boolean = when (this) {
        Pending -> next == Declared || next is Rejected || next == ToConfirm || next == Voided
        is Rejected, Voided -> next == Pending
        ToConfirm -> next == Pending || next == Declared
        Declared -> false
    }
}

/**
 * Storage of the stock movements. A movement is never deleted nor changed; only its status moves.
 * The stock of an item is the sum of its movements that count (see [MovementStatus.counts]).
 */
interface StockRepository {
    /**
     * @param content the movement to store.
     * @return the stored movement, [MovementStatus.Pending].
     */
    suspend fun record(content: StockMovementContent): StockMovement

    /**
     * @param id an identifier.
     * @return the movement, or null if there is none.
     */
    suspend fun find(id: MovementId): StockMovement?

    /**
     * @param id a movement.
     * @return its status, or null if there is no such movement.
     */
    suspend fun statusOf(id: MovementId): MovementStatus?

    /**
     * @param id a movement.
     * @param status its new status.
     * @param at when it changed.
     * @throws IllegalStateException if the movement may not go to [status] (see [MovementStatus.canBecome]).
     */
    suspend fun setStatus(id: MovementId, status: MovementStatus, at: Instant)

    /** @return how many movements were recorded: the next one takes the following rank. */
    suspend fun count(): Long

    /**
     * @param itemId an item.
     * @return its stock: the sum of its movements that count, zero when it has none.
     */
    suspend fun quantityOf(itemId: ItemId): BigDecimal

    /** @return the stock of every item that has a movement. */
    suspend fun quantities(): Map<ItemId, BigDecimal>

    /**
     * @param itemId an item.
     * @param limit the most movements to return.
     * @return its movements, the most recent first.
     */
    suspend fun movementsOf(itemId: ItemId, limit: Int): List<StockMovement>

    /**
     * @return the last unit purchase price of each item: the one of its most recent entry EN or
     * EI that counts. An item that never entered the stock that way has none.
     */
    suspend fun lastEntryCosts(): Map<ItemId, Money>

    /**
     * @param invoiceId an invoice or a credit note.
     * @return the movements written with it, the oldest first.
     */
    suspend fun ofInvoice(invoiceId: InvoiceId): List<StockMovement>

    /** @return the movements a manager has to decide about, the oldest first. */
    suspend fun toResolve(): List<StockMovement>
}
