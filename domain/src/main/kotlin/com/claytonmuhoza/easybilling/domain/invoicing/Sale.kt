package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.RoundingPolicy
import java.math.BigDecimal
import java.time.Instant

/** Identifier of a sale that is not issued yet. */
@JvmInline
value class DraftId(val value: Long)

/**
 * How an invoice is paid (`payment_type`).
 *
 * @property code the value sent to the OBR.
 */
enum class PaymentType(val code: String) {
    /** Cash. */
    CASH("1"),

    /** Bank. */
    BANK("2"),

    /** Credit: paid later. */
    CREDIT("3"),

    /** Any other way. */
    OTHER("4"),
}

/**
 * A discount on a line (UC-C03): it lowers the unit price before the taxes are computed. The OBR
 * has no field for it: only the lowered price is sent.
 */
sealed interface Discount {
    /**
     * @param unitPrice the unit price of the item, with or without taxes as the item says.
     * @param rounding the rounding of the lowered price.
     * @return the unit price once the discount is taken off.
     */
    fun applyTo(unitPrice: Money, rounding: RoundingPolicy): Money

    /**
     * A share of the unit price.
     *
     * @property rate the share in percent: `10` for 10 %.
     */
    data class Percent(val rate: BigDecimal) : Discount {
        override fun applyTo(unitPrice: Money, rounding: RoundingPolicy): Money {
            val kept = BigDecimal.ONE - rate.movePointLeft(2)
            return Money.of(unitPrice.amount * kept, unitPrice.currency).rounded(rounding)
        }

        override fun equals(other: Any?): Boolean = other is Percent && rate.compareTo(other.rate) == 0

        override fun hashCode(): Int = rate.stripTrailingZeros().hashCode()
    }

    /**
     * An amount taken off each unit.
     *
     * @property amount the amount, in the currency of the price.
     */
    data class PerUnit(val amount: BigDecimal) : Discount {
        override fun applyTo(unitPrice: Money, rounding: RoundingPolicy): Money =
            Money.of(unitPrice.amount - amount, unitPrice.currency).rounded(rounding)

        override fun equals(other: Any?): Boolean = other is PerUnit && amount.compareTo(other.amount) == 0

        override fun hashCode(): Int = amount.stripTrailingZeros().hashCode()
    }
}

/**
 * A line of a sale that is not issued yet. It points to the item: its price and its taxes are read
 * from the catalogue until the sale is issued.
 *
 * @property itemId the item sold; a sale has one line per item.
 * @property quantity the quantity sold.
 * @property discount the discount on the unit price, or null.
 * @property freeAmounts the amounts typed for the taxes of the item computed as a free amount.
 * @property approval the approval of [discount] by a manager, or null; it always approves
 * [discount] itself.
 */
data class DraftLine(
    val itemId: ItemId,
    val quantity: Quantity,
    val discount: Discount? = null,
    val freeAmounts: Map<TaxKind, Money> = emptyMap(),
    val approval: DiscountApproval? = null,
)

/**
 * A sale that is not issued yet (UC-C02). It has no number, is never sent to the OBR, and may be
 * changed or deleted freely.
 *
 * @property id identifier in the local storage.
 * @property customerId the customer, or null for the counter customer.
 * @property paymentType how the sale is paid.
 * @property lines the lines, in the order the items were added.
 * @property held whether the sale was put on hold; at most one sale is not: the one being typed.
 * @property updatedAt when the sale last changed.
 */
data class SaleDraft(
    val id: DraftId,
    val customerId: CustomerId? = null,
    val paymentType: PaymentType = PaymentType.CASH,
    val lines: List<DraftLine> = emptyList(),
    val held: Boolean = false,
    val updatedAt: Instant,
)

/** Storage of the sales that are not issued yet. */
interface SaleDraftRepository {
    /**
     * @param now when the sale starts.
     * @return a new empty sale, not on hold.
     */
    suspend fun create(now: Instant): SaleDraft

    /**
     * @param id an identifier.
     * @return the sale, or null if there is none.
     */
    suspend fun find(id: DraftId): SaleDraft?

    /** @return the sale being typed, or null if there is none. */
    suspend fun current(): SaleDraft?

    /** @return the sales on hold, the most recently changed first. */
    suspend fun held(): List<SaleDraft>

    /** @param draft the sale, which replaces the stored one with the same identifier. */
    suspend fun save(draft: SaleDraft)

    /** @param id the sale to forget. */
    suspend fun delete(id: DraftId)
}

/** A way to give a discount. */
enum class DiscountKind {
    /** A percentage of the unit price. */
    PERCENT,

    /** An amount taken off each unit. */
    PER_UNIT,
}

/** A broken rule of what was typed for a line. */
enum class SaleLineProblem {
    /** The quantity is not a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most. */
    QUANTITY_INVALID,

    /** The discount is not a percentage between 0 and 100, or not an amount above zero. */
    DISCOUNT_INVALID,

    /** A tax amount is not a number, is negative or has too many decimals. */
    FREE_AMOUNT_INVALID,
}

/** Result of [SaleLineForm.check]. */
sealed interface SaleLineCheck {
    /**
     * Every rule holds.
     *
     * @property quantity the quantity.
     * @property discount the discount, or null.
     * @property freeAmounts the tax amounts typed, without the empty ones.
     */
    data class Valid(
        val quantity: Quantity,
        val discount: Discount?,
        val freeAmounts: Map<TaxKind, Money>,
    ) : SaleLineCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<SaleLineProblem>) : SaleLineCheck
}

/**
 * A line as typed by the user, not checked yet. A comma is a decimal point.
 *
 * @property quantity the quantity.
 * @property discountKind how the discount is given, or null for no discount.
 * @property discountValue the percentage or the amount of the discount.
 * @property freeAmounts the amounts of the taxes computed as a free amount; empty for none.
 */
data class SaleLineForm(
    val quantity: String = "1",
    val discountKind: DiscountKind? = null,
    val discountValue: String = "",
    val freeAmounts: Map<TaxKind, String> = emptyMap(),
) {
    /** @return the line, or every broken rule. */
    fun check(): SaleLineCheck {
        val problems = mutableSetOf<SaleLineProblem>()
        val checkedQuantity = number(quantity)?.takeIf { it.signum() > 0 }
        if (checkedQuantity == null) problems += SaleLineProblem.QUANTITY_INVALID
        val checkedDiscount = discount()
        if (discountKind != null && checkedDiscount == null) problems += SaleLineProblem.DISCOUNT_INVALID
        val typed = freeAmounts.filterValues { it.isNotBlank() }.mapValues { number(it.value) }
        if (typed.values.any { it == null }) problems += SaleLineProblem.FREE_AMOUNT_INVALID

        return if (checkedQuantity != null && problems.isEmpty()) {
            val amounts = typed.mapValues { Money.of(checkNotNull(it.value), Currency.BIF) }
            SaleLineCheck.Valid(Quantity.of(checkedQuantity), checkedDiscount, amounts)
        } else {
            SaleLineCheck.Invalid(problems)
        }
    }

    private fun discount(): Discount? {
        val value = number(discountValue)?.takeIf { it.signum() > 0 }
        return when (discountKind) {
            null -> null
            DiscountKind.PERCENT -> value?.takeIf { it < HUNDRED }?.let { Discount.Percent(it) }
            DiscountKind.PER_UNIT -> value?.let { Discount.PerUnit(it) }
        }
    }

    /** @return [text] as a number that is not negative, with [MAX_DECIMALS] decimals at most, or null. */
    private fun number(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.takeIf { it.signum() >= 0 && it.scale() <= MAX_DECIMALS }

    /** Ways to start a form. */
    companion object {
        /** Most decimals of a quantity, a discount or a tax amount. */
        const val MAX_DECIMALS = 2

        private val HUNDRED = BigDecimal(100)

        /**
         * @param line a line of a sale.
         * @return the form that gives it back.
         */
        fun from(line: DraftLine): SaleLineForm = SaleLineForm(
            quantity = line.quantity.toPlainString(),
            discountKind = when (line.discount) {
                null -> null
                is Discount.Percent -> DiscountKind.PERCENT
                is Discount.PerUnit -> DiscountKind.PER_UNIT
            },
            discountValue = when (val discount = line.discount) {
                null -> ""
                is Discount.Percent -> discount.rate.stripTrailingZeros().toPlainString()
                is Discount.PerUnit -> discount.amount.stripTrailingZeros().toPlainString()
            },
            freeAmounts = line.freeAmounts.mapValues { it.value.toPlainString() },
        )
    }
}
