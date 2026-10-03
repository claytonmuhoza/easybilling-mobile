package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.RoundingPolicy
import java.math.BigDecimal
import java.math.MathContext

/** Whether a unit price includes the taxes (decision D09). */
enum class PriceMode {
    /** The price is without any tax: the taxes are added to it. */
    EXCLUDING_TAXES,

    /** The price includes every tax: the line total is exactly the price times the quantity. */
    INCLUDING_TAXES,
}

/**
 * What a line is computed from.
 *
 * @property quantity the quantity sold (Q).
 * @property unitPrice the unit price, with or without taxes as [priceMode] says.
 * @property priceMode whether [unitPrice] includes the taxes.
 * @property taxes how each tax of the line is computed; a missing tax is zero.
 * @property freeAmounts the amounts typed for the taxes computed as a free amount; a missing one
 * is zero. They are line amounts, not amounts per unit.
 */
data class LineInput(
    val quantity: Quantity,
    val unitPrice: Money,
    val priceMode: PriceMode,
    val taxes: Map<TaxKind, TaxComputation> = emptyMap(),
    val freeAmounts: Map<TaxKind, Money> = emptyMap(),
)

/**
 * The amounts of a line, as sent to the OBR. Each one is rounded, and the sums are made on the
 * rounded values, so every identity of the formula holds exactly.
 *
 * @property unitPrice `item_price`: the unit price without taxes.
 * @property price the unit price times the quantity, without taxes.
 * @property consumptionTax `item_ct`.
 * @property ott `item_ott_tax`.
 * @property tsce `item_tsce_tax`.
 * @property priceWithoutVat `item_price_nvat` = price + TC + OTT + TSCE.
 * @property vat `vat` = price without VAT × rate.
 * @property priceWithVat `item_price_wvat` = price without VAT + VAT.
 * @property flatRateWithholding `item_tl` (PFL).
 * @property total `item_total_amount` = price with VAT + PFL.
 */
data class LineAmounts(
    val unitPrice: Money,
    val price: Money,
    val consumptionTax: Money,
    val ott: Money,
    val tsce: Money,
    val priceWithoutVat: Money,
    val vat: Money,
    val priceWithVat: Money,
    val flatRateWithholding: Money,
    val total: Money,
)

/** Computes the amounts of an invoice line. */
fun interface LineTaxCalculator {
    /**
     * @param line what the line is computed from.
     * @param registrations the taxes the business is registered for: the others are zero.
     * @return the amounts of the line.
     * @throws IllegalArgumentException if a free amount is in another currency than the price, or
     * if a price including taxes does not cover its fixed taxes.
     */
    fun compute(line: LineInput, registrations: TaxRegistrations): LineAmounts
}

/**
 * The formula imposed by the OBR (`docs/architecture.md` § 7.1):
 * HTVA = PU × Q + TC + OTT + TSCE, TVA = HTVA × rate, TVAC = HTVA + TVA, total = TVAC + PFL.
 *
 * A price excluding taxes is computed in that order. For a price including taxes, the exact
 * price without taxes is found first, then the components are deduced from the total, so the
 * total is exactly the price times the quantity (§ 7.2).
 *
 * @param rounding the rounding of each amount (decision D08).
 */
class ObrLineTaxCalculator(private val rounding: RoundingPolicy = ObrRoundingPolicy) : LineTaxCalculator {

    override fun compute(line: LineInput, registrations: TaxRegistrations): LineAmounts {
        val currency = line.unitPrice.currency
        require(line.freeAmounts.values.all { it.currency == currency }) { "Every amount of a line is in $currency" }
        val taxes = line.taxes.filterKeys { it.appliesTo(registrations) }
        val terms = Terms(taxes, line.freeAmounts.mapValues { it.value.amount }, line.quantity.value)
        val amounts = when (line.priceMode) {
            PriceMode.EXCLUDING_TAXES -> excludingTaxes(line.unitPrice.amount, terms)
            PriceMode.INCLUDING_TAXES -> includingTaxes(line.unitPrice.amount, terms)
        }
        return amounts.toMoney(currency)
    }

    private fun excludingTaxes(unitPrice: BigDecimal, terms: Terms): Amounts {
        val roundedUnitPrice = round(unitPrice)
        val price = round(roundedUnitPrice * terms.quantity)
        val additive = ADDITIVE.associateWith { round(terms.additive(it, price)) }
        val priceWithoutVat = price + additive.values.sum()
        val vat = round(priceWithoutVat * terms.vatRate)
        val priceWithVat = priceWithoutVat + vat
        val flatRate = round(terms.flatRate(price, priceWithoutVat, priceWithVat))
        return Amounts(roundedUnitPrice, price, additive, priceWithoutVat, vat, priceWithVat, flatRate)
    }

    private fun includingTaxes(unitPrice: BigDecimal, terms: Terms): Amounts {
        val total = round(unitPrice * terms.quantity)
        val exactPrice = terms.priceFromTotal(total)
        require(exactPrice.signum() >= 0) { "The price including taxes does not cover its fixed taxes" }
        val exactWithoutVat = exactPrice + ADDITIVE.sumOf { terms.additive(it, exactPrice) }
        val exactWithVat = exactWithoutVat * (BigDecimal.ONE + terms.vatRate)
        val flatRate = round(terms.flatRate(exactPrice, exactWithoutVat, exactWithVat))
        val priceWithVat = total - flatRate
        val priceWithoutVat = round(priceWithVat.divide(BigDecimal.ONE + terms.vatRate, MATH))
        val additive = ADDITIVE.associateWith { round(terms.additive(it, exactPrice)) }
        val price = priceWithoutVat - additive.values.sum()
        val roundedUnitPrice = round(price.divide(terms.quantity, MATH))
        val vat = priceWithVat - priceWithoutVat
        return Amounts(roundedUnitPrice, price, additive, priceWithoutVat, vat, priceWithVat, flatRate)
    }

    private fun round(amount: BigDecimal): BigDecimal = rounding.round(amount)

    private fun TaxKind.appliesTo(registrations: TaxRegistrations): Boolean = when (this) {
        TaxKind.VAT -> registrations.vat
        TaxKind.CONSUMPTION_TAX -> registrations.consumptionTax
        TaxKind.FLAT_RATE_WITHHOLDING -> registrations.flatRateWithholding
        // The specification has no registration for these two taxes.
        TaxKind.OTT, TaxKind.TSCE -> true
    }

    /** The taxes of a line, as numbers. */
    private class Terms(
        private val taxes: Map<TaxKind, TaxComputation>,
        private val freeAmounts: Map<TaxKind, BigDecimal>,
        val quantity: BigDecimal,
    ) {
        /** The VAT rate as a fraction: 0.18 for 18 %. */
        val vatRate: BigDecimal = rateOf(TaxKind.VAT)

        /** @return the rate of a tax computed as a percentage, as a fraction; zero otherwise. */
        private fun rateOf(kind: TaxKind): BigDecimal =
            (taxes[kind] as? TaxComputation.Percentage)?.rate?.let(::fraction) ?: BigDecimal.ZERO

        /** @return the exact amount of a tax added before the VAT, for a [price] of PU × Q. */
        fun additive(kind: TaxKind, price: BigDecimal): BigDecimal = when (val tax = taxes[kind]) {
            is TaxComputation.Percentage -> price * fraction(tax.rate)
            else -> fixed(kind, tax)
        }

        /** @return the exact PFL, from the three amounts a percentage may apply to. */
        fun flatRate(price: BigDecimal, withoutVat: BigDecimal, withVat: BigDecimal): BigDecimal =
            when (val tax = taxes[TaxKind.FLAT_RATE_WITHHOLDING]) {
                is TaxComputation.Percentage -> fraction(tax.rate) * when (tax.base) {
                    PercentageBase.PRICE -> price
                    PercentageBase.PRICE_WITHOUT_VAT -> withoutVat
                    PercentageBase.PRICE_WITH_VAT -> withVat
                }
                else -> fixed(TaxKind.FLAT_RATE_WITHHOLDING, tax)
            }

        /**
         * Every amount of the formula is linear in the price PU × Q: total = slope × price + constant.
         *
         * @return the exact price PU × Q giving [total].
         */
        fun priceFromTotal(total: BigDecimal): BigDecimal {
            val additiveRates = ADDITIVE.sumOf(::rateOf)
            val additiveFixed = ADDITIVE.sumOf { fixed(it, taxes[it]) }
            val vatFactor = BigDecimal.ONE + vatRate
            val flatRate = taxes[TaxKind.FLAT_RATE_WITHHOLDING]
            val withoutVatFactor = when (flatRate) {
                is TaxComputation.Percentage -> when (flatRate.base) {
                    PercentageBase.PRICE -> vatFactor
                    PercentageBase.PRICE_WITHOUT_VAT -> vatFactor + fraction(flatRate.rate)
                    PercentageBase.PRICE_WITH_VAT -> vatFactor * (BigDecimal.ONE + fraction(flatRate.rate))
                }
                else -> vatFactor
            }
            val priceShare = (flatRate as? TaxComputation.Percentage)
                ?.takeIf { it.base == PercentageBase.PRICE }
                ?.let { fraction(it.rate) } ?: BigDecimal.ZERO
            val slope = (BigDecimal.ONE + additiveRates) * withoutVatFactor + priceShare
            val constant = additiveFixed * withoutVatFactor + fixed(TaxKind.FLAT_RATE_WITHHOLDING, flatRate)
            return (total - constant).divide(slope, MATH)
        }

        private fun fixed(kind: TaxKind, tax: TaxComputation?): BigDecimal = when (tax) {
            is TaxComputation.PerUnit -> tax.amount * quantity
            TaxComputation.FreeAmount -> freeAmounts[kind] ?: BigDecimal.ZERO
            is TaxComputation.Percentage, null -> BigDecimal.ZERO
        }

        private fun fraction(percent: BigDecimal): BigDecimal = percent.movePointLeft(2)
    }

    /** The rounded amounts, before they get their currency. */
    private class Amounts(
        val unitPrice: BigDecimal,
        val price: BigDecimal,
        val additive: Map<TaxKind, BigDecimal>,
        val priceWithoutVat: BigDecimal,
        val vat: BigDecimal,
        val priceWithVat: BigDecimal,
        val flatRate: BigDecimal,
    ) {
        fun toMoney(currency: Currency): LineAmounts {
            fun money(amount: BigDecimal) = Money.of(amount, currency)
            return LineAmounts(
                unitPrice = money(unitPrice),
                price = money(price),
                consumptionTax = money(additive.getValue(TaxKind.CONSUMPTION_TAX)),
                ott = money(additive.getValue(TaxKind.OTT)),
                tsce = money(additive.getValue(TaxKind.TSCE)),
                priceWithoutVat = money(priceWithoutVat),
                vat = money(vat),
                priceWithVat = money(priceWithVat),
                flatRateWithholding = money(flatRate),
                total = money(priceWithVat + flatRate),
            )
        }
    }

    private companion object {
        /** The taxes added to the price before the VAT. */
        val ADDITIVE = listOf(TaxKind.CONSUMPTION_TAX, TaxKind.OTT, TaxKind.TSCE)

        /** Precision of the exact divisions, far beyond two decimals. */
        val MATH: MathContext = MathContext.DECIMAL128

        fun Collection<BigDecimal>.sum(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)
    }
}

/**
 * The totals of an invoice: each column is the sum of the rounded amounts of its lines.
 *
 * @property price sum of the prices without taxes.
 * @property consumptionTax sum of the TC.
 * @property ott sum of the OTT.
 * @property tsce sum of the TSCE.
 * @property priceWithoutVat sum of the prices without VAT.
 * @property vat sum of the VAT.
 * @property priceWithVat sum of the prices with VAT.
 * @property flatRateWithholding sum of the PFL.
 * @property total sum of the line totals.
 */
data class InvoiceTotals(
    val price: Money,
    val consumptionTax: Money,
    val ott: Money,
    val tsce: Money,
    val priceWithoutVat: Money,
    val vat: Money,
    val priceWithVat: Money,
    val flatRateWithholding: Money,
    val total: Money,
) {
    /** Ways to build the totals. */
    companion object {
        /**
         * @param lines the lines of the invoice, all in [currency].
         * @param currency the currency of the invoice.
         * @return the totals.
         * @throws IllegalArgumentException if a line is in another currency.
         */
        fun of(
            lines: List<LineAmounts>,
            currency: Currency = lines.firstOrNull()?.total?.currency ?: Currency.BIF,
        ): InvoiceTotals {
            fun sum(column: (LineAmounts) -> Money) = lines.map(column).fold(Money.zero(currency), Money::plus)
            return InvoiceTotals(
                price = sum(LineAmounts::price),
                consumptionTax = sum(LineAmounts::consumptionTax),
                ott = sum(LineAmounts::ott),
                tsce = sum(LineAmounts::tsce),
                priceWithoutVat = sum(LineAmounts::priceWithoutVat),
                vat = sum(LineAmounts::vat),
                priceWithVat = sum(LineAmounts::priceWithVat),
                flatRateWithholding = sum(LineAmounts::flatRateWithholding),
                total = sum(LineAmounts::total),
            )
        }
    }
}
