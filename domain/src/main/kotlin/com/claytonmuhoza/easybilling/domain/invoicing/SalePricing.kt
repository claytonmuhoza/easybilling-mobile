package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.stock.InvoiceStock
import java.math.BigDecimal

/** Why a sale cannot be issued as it is. */
enum class SaleProblem {
    /** The sale has no line. */
    EMPTY,

    /** The customer was archived since it was chosen. */
    CUSTOMER_ARCHIVED,

    /** The item was archived since it was added. */
    ITEM_ARCHIVED,

    /** The business is registered for the VAT and the item has none (see `Item.needsVat`). */
    ITEM_WITHOUT_VAT,

    /** The discount leaves no price: the unit price would be zero or less. */
    DISCOUNT_TOO_HIGH,

    /** A price including taxes does not cover the fixed taxes of the line. */
    PRICE_BELOW_TAXES,

    /** The item is kept in stock and the stock has less than the quantity of the line (UC-F09). */
    STOCK_INSUFFICIENT,
}

/**
 * A line of a sale with its amounts.
 *
 * @property line the line as typed.
 * @property item the item sold, as the catalogue has it now.
 * @property netUnitPrice the unit price of the item once the discount is taken off, with or without
 * taxes as the item says.
 * @property computations how each tax of the line is computed today.
 * @property amounts the amounts of the line, or null when a problem prevents the calculation.
 * @property problems why the line cannot be issued; empty when it can.
 * @property available what the sale may take of the item now, or null when it is not kept in stock.
 */
data class PricedLine(
    val line: DraftLine,
    val item: Item,
    val netUnitPrice: Money,
    val computations: Map<TaxKind, TaxComputation>,
    val amounts: LineAmounts?,
    val problems: Set<SaleProblem>,
    val available: BigDecimal? = null,
) {
    /** The taxes whose amount is typed on the line. */
    val freeTaxes: Set<TaxKind> get() = computations.filterValues { it == TaxComputation.FreeAmount }.keys

    /**
     * @param registrations the taxes the business is registered for.
     * @return the VAT rate applied to the line, in percent, or null when no VAT applies.
     */
    fun vatRate(registrations: TaxRegistrations): BigDecimal? =
        (computations[TaxKind.VAT] as? TaxComputation.Percentage)?.rate?.takeIf { registrations.vat }
}

/**
 * A sale with its amounts, as the cash register shows it and as it would be issued now.
 *
 * @property draft the sale as typed.
 * @property customer the customer: the counter customer when none was chosen.
 * @property registrations the taxes the business is registered for; none while no profile is saved.
 * @property lines the lines with their amounts.
 * @property totals the sums of the lines that could be computed.
 * @property problems why the sale itself cannot be issued; the lines have their own.
 */
data class PricedSale(
    val draft: SaleDraft,
    val customer: Customer,
    val registrations: TaxRegistrations,
    val lines: List<PricedLine>,
    val totals: InvoiceTotals,
    val problems: Set<SaleProblem>,
) {
    /** Whether nothing prevents the sale from being issued. */
    val issuable: Boolean get() = problems.isEmpty() && lines.all { it.problems.isEmpty() }
}

/**
 * Computes a sale with the prices and the tax rates of today (UC-C01, steps 1 to 3).
 *
 * @param items where the items are kept.
 * @param taxes where the taxes are kept.
 * @param customers where the customers are kept.
 * @param profiles where the tax registrations are kept.
 * @param time source of the current day.
 * @param stock tells what a sale may take of an item kept in stock.
 * @param calculator the formula of the OBR.
 */
class PriceSale(
    private val items: ItemRepository,
    private val taxes: TaxRepository,
    private val customers: CustomerRepository,
    private val profiles: TaxProfileRepository,
    private val time: TimeProvider,
    private val stock: InvoiceStock,
    private val calculator: LineTaxCalculator = ObrLineTaxCalculator(),
) {
    /**
     * @param draft a sale that is not issued yet.
     * @return the sale with its amounts and what prevents it from being issued. A line whose item no
     * longer exists is left out.
     */
    suspend operator fun invoke(draft: SaleDraft): PricedSale {
        val registrations = profiles.current()?.profile?.taxes ?: TaxRegistrations()
        val customer = draft.customerId?.let { customers.find(it) } ?: customers.counter()
        val lines = draft.lines.mapNotNull { line ->
            items.find(line.itemId)?.let { priced(line, it, registrations).withStock(draft) }
        }
        return PricedSale(
            draft = draft,
            customer = customer,
            registrations = registrations,
            lines = lines,
            totals = InvoiceTotals.of(lines.mapNotNull { it.amounts }),
            problems = setOfNotNull(
                SaleProblem.EMPTY.takeIf { lines.isEmpty() },
                SaleProblem.CUSTOMER_ARCHIVED.takeIf { customer.archived },
            ),
        )
    }

    private suspend fun priced(line: DraftLine, item: Item, registrations: TaxRegistrations): PricedLine {
        val today = BusinessCalendar.today(time)
        val computations = item.details.taxes
            .mapNotNull { (kind, id) -> taxes.find(id)?.computationAt(today)?.let { kind to it } }
            .toMap()
        val listPrice = item.details.price
        val netUnitPrice = line.discount?.applyTo(listPrice, ObrRoundingPolicy) ?: listPrice
        val problems = mutableSetOf<SaleProblem>()
        if (item.archived) problems += SaleProblem.ITEM_ARCHIVED
        if (item.needsVat(registrations)) problems += SaleProblem.ITEM_WITHOUT_VAT
        val amounts = if (netUnitPrice.amount.signum() <= 0) {
            null.also { problems += SaleProblem.DISCOUNT_TOO_HIGH }
        } else {
            val input = LineInput(line.quantity, netUnitPrice, item.details.priceMode, computations, line.freeAmounts)
            amounts(input, registrations) ?: null.also { problems += SaleProblem.PRICE_BELOW_TAXES }
        }
        return PricedLine(line, item, netUnitPrice, computations, amounts, problems)
    }

    /** @return the line with what the sale may take of its item, and the problem of taking more. */
    private suspend fun PricedLine.withStock(draft: SaleDraft): PricedLine {
        if (!item.details.stockTracked) return this
        val available = stock.available(item.id, draft.replacement?.invoiceId)
        val short = line.quantity.value > available
        return copy(
            available = available,
            problems = if (short) problems + SaleProblem.STOCK_INSUFFICIENT else problems,
        )
    }

    /** @return the amounts, or null when the price including taxes does not cover its fixed taxes. */
    private fun amounts(input: LineInput, registrations: TaxRegistrations): LineAmounts? =
        try {
            calculator.compute(input, registrations)
        } catch (_: IllegalArgumentException) {
            null
        }
}
