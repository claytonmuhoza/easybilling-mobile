package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.RoundingPolicy
import java.math.BigDecimal
import java.math.MathContext

/**
 * Why a credit note is issued (UC-E03).
 *
 * @property label how `cn_motif` names it: documents and what is sent to the OBR are in French.
 */
enum class CreditReasonType(val label: String) {
    /** The invoice has a mistake. */
    ERROR("Erreur"),

    /** The customer brought goods back. */
    GOODS_RETURN("Retour marchandises"),

    /** A lower price for goods that are not as agreed. */
    REBATE("Rabais"),

    /** A commercial discount. */
    DISCOUNT("Remise"),

    /** A reduction for the volume bought over a period. */
    VOLUME_REBATE("Ristourne"),
}

/**
 * The reason of a credit note.
 *
 * @property type the kind of reason.
 * @property detail what the user adds, or empty.
 */
data class CreditReason(val type: CreditReasonType, val detail: String = "") {
    /** `cn_motif`: the type, followed by the detail when there is one (D52). */
    val motif: String get() = if (detail.isEmpty()) type.label else type.label + SEPARATOR + detail

    /** The rules of a reason. */
    companion object {
        /** Most characters of `cn_motif` (`docs/architecture.md` § 7.4). */
        const val MAX_LENGTH = CancellationReason.MAX_LENGTH

        private const val SEPARATOR = " : "

        /**
         * @param type the kind of reason.
         * @return the most characters a detail may have for `cn_motif` to hold in [MAX_LENGTH].
         */
        fun maxDetailLength(type: CreditReasonType): Int = MAX_LENGTH - type.label.length - SEPARATOR.length

        /**
         * @param type the kind chosen, or null when none is.
         * @param typedDetail the detail as typed.
         * @return the rule broken, or null: a type is required and the detail must fit.
         */
        fun problem(type: CreditReasonType?, typedDetail: String): ReasonProblem? = when {
            type == null -> ReasonProblem.BLANK
            typedDetail.trim().length > maxDetailLength(type) -> ReasonProblem.TOO_LONG
            else -> null
        }
    }
}

/**
 * What makes an invoice a credit note.
 *
 * @property invoiceRef `invoice_ref`: the number of the invoice it credits.
 * @property reason why; its motif is `cn_motif`.
 */
data class CreditNote(val invoiceRef: String, val reason: CreditReason)

/**
 * A line of a credit note as typed, not checked yet. A comma is a decimal point.
 *
 * @property itemId the item of the line of the invoice that is credited.
 * @property quantity the quantity credited.
 * @property unitPrice the unit price credited, with or without taxes as the line of the invoice.
 */
data class CreditLineForm(val itemId: ItemId, val quantity: String, val unitPrice: String)

/** A broken rule of a line of a credit note. */
enum class CreditLineProblem {
    /** The quantity is not a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most. */
    QUANTITY_INVALID,

    /** The quantity is more than what is left to credit on the line. */
    QUANTITY_TOO_HIGH,

    /** The unit price is not a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most. */
    PRICE_INVALID,

    /** The unit price is more than the one invoiced. */
    PRICE_TOO_HIGH,
}

/**
 * A line of an invoice and what is left to credit on it.
 *
 * @property line the line of the invoice.
 * @property netUnitPrice the unit price invoiced once the discount is taken off, with or without
 * taxes as the line says: the most a unit is credited for.
 * @property remaining the quantity no credit note took yet; zero when there is none left.
 */
data class CreditableLine(val line: InvoiceLine, val netUnitPrice: Money, val remaining: BigDecimal)

/**
 * An invoice and what is left to credit on it.
 *
 * @property invoice the invoice.
 * @property lines its lines, in order.
 * @property remainingAmount its total less the totals of its credit notes: the most the next
 * credit note may be.
 */
data class Creditable(val invoice: Invoice, val lines: List<CreditableLine>, val remainingAmount: Money)

/**
 * A line of a credit note once checked.
 *
 * @property form what was typed.
 * @property problems the rules it breaks.
 * @property line the line of the credit note, or null when a rule is broken.
 */
data class PricedCreditLine(val form: CreditLineForm, val problems: Set<CreditLineProblem>, val line: InvoiceLine?)

/**
 * The lines of a credit note once checked.
 *
 * @property lines the lines.
 * @property total the total of the lines that break no rule.
 * @property aboveCap whether [total] is more than what is left to credit on the invoice.
 */
data class PricedCredit(val lines: List<PricedCreditLine>, val total: Money, val aboveCap: Boolean) {
    /** Whether a credit note can be issued: a line at least, no broken rule, within the cap. */
    val issuable: Boolean get() = lines.isNotEmpty() && lines.all { it.problems.isEmpty() } && !aboveCap
}

/** Why an invoice cannot be credited now. */
enum class CreditProblem {
    /** There is no such invoice. */
    NOT_FOUND,

    /** It is not a normal invoice (FN): the OBR credits nothing else. */
    NOT_AN_INVOICE,

    /** The OBR rejected it: it does not exist there. */
    REJECTED,

    /** It is cancelled. */
    CANCELLED,

    /** Its cancellation, or its replacement, waits for the OBR. */
    CANCELLING,

    /** A sale that replaces it is being typed or is on hold. */
    REPLACING,

    /** Its credit notes already took every quantity, or its whole total. */
    FULLY_CREDITED,
}

/**
 * @param number the number of an invoice.
 * @return its credit notes that count: every one the OBR did not reject (D52).
 */
suspend fun InvoiceRepository.countedCreditNotes(number: String): List<Invoice> =
    creditNotesOf(number).filter { statusOf(it.id) !is InvoiceStatus.Rejected }

/**
 * Computes the amounts of a line of a credit note from the line of the invoice (D52). A line
 * credited in full at its price copies its amounts. Otherwise the taxes added before the VAT and
 * the PFL follow the share of the price credited, and the VAT is computed again at the rate of the
 * line, so every identity of the formula of the OBR holds.
 *
 * @param rounding the rounding of each amount.
 */
class CreditLineCalculator(private val rounding: RoundingPolicy = ObrRoundingPolicy) {

    /**
     * @param line a line of an invoice.
     * @return its unit price once the discount is taken off, with or without taxes as the line says.
     */
    fun netUnitPrice(line: InvoiceLine): Money =
        (line.discount?.applyTo(line.listUnitPrice, rounding) ?: line.listUnitPrice).rounded(rounding)

    /**
     * @param line the line of the invoice.
     * @param quantity the quantity credited.
     * @param unitPrice the unit price credited, with or without taxes as [line] says.
     * @return the amounts of the line of the credit note.
     */
    fun amounts(line: InvoiceLine, quantity: Quantity, unitPrice: Money): LineAmounts {
        val full = quantity == line.quantity && unitPrice == netUnitPrice(line)
        return when {
            full -> line.amounts
            line.priceMode == PriceMode.EXCLUDING_TAXES -> excludingTaxes(line, quantity, unitPrice)
            else -> includingTaxes(line, quantity, unitPrice)
        }
    }

    private fun excludingTaxes(line: InvoiceLine, quantity: Quantity, unitPrice: Money): LineAmounts {
        val roundedUnitPrice = unitPrice.rounded(rounding)
        val price = (roundedUnitPrice * quantity).rounded(rounding)
        val share = Share(price.amount, line.amounts.price.amount)
        val consumptionTax = share.of(line.amounts.consumptionTax)
        val ott = share.of(line.amounts.ott)
        val tsce = share.of(line.amounts.tsce)
        val priceWithoutVat = price + consumptionTax + ott + tsce
        val vat = Money.of(priceWithoutVat.amount * vatFraction(line), price.currency).rounded(rounding)
        val priceWithVat = priceWithoutVat + vat
        val flatRate = share.of(line.amounts.flatRateWithholding)
        return LineAmounts(
            unitPrice = roundedUnitPrice,
            price = price,
            consumptionTax = consumptionTax,
            ott = ott,
            tsce = tsce,
            priceWithoutVat = priceWithoutVat,
            vat = vat,
            priceWithVat = priceWithVat,
            flatRateWithholding = flatRate,
            total = priceWithVat + flatRate,
        )
    }

    /** The total is exactly the price times the quantity; the components are deduced from it. */
    private fun includingTaxes(line: InvoiceLine, quantity: Quantity, unitPrice: Money): LineAmounts {
        val total = (unitPrice * quantity).rounded(rounding)
        val currency = total.currency
        val share = Share(total.amount, line.amounts.total.amount)
        val flatRate = share.of(line.amounts.flatRateWithholding)
        val priceWithVat = total - flatRate
        val vatFactor = BigDecimal.ONE + vatFraction(line)
        val priceWithoutVat = Money.of(priceWithVat.amount.divide(vatFactor, MATH), currency).rounded(rounding)
        val consumptionTax = share.of(line.amounts.consumptionTax)
        val ott = share.of(line.amounts.ott)
        val tsce = share.of(line.amounts.tsce)
        val price = priceWithoutVat - consumptionTax - ott - tsce
        return LineAmounts(
            unitPrice = Money.of(price.amount.divide(quantity.value, MATH), currency).rounded(rounding),
            price = price,
            consumptionTax = consumptionTax,
            ott = ott,
            tsce = tsce,
            priceWithoutVat = priceWithoutVat,
            vat = priceWithVat - priceWithoutVat,
            priceWithVat = priceWithVat,
            flatRateWithholding = flatRate,
            total = total,
        )
    }

    private fun vatFraction(line: InvoiceLine): BigDecimal = line.vatRate?.movePointLeft(2) ?: BigDecimal.ZERO

    /** The share [credited] is of [invoiced]. */
    private inner class Share(private val credited: BigDecimal, private val invoiced: BigDecimal) {
        /** @return this share of [amount], rounded. */
        fun of(amount: Money): Money =
            if (invoiced.signum() == 0) {
                Money.zero(amount.currency)
            } else {
                Money.of((amount.amount * credited).divide(invoiced, MATH), amount.currency).rounded(rounding)
            }
    }

    private companion object {
        /** Precision of the exact divisions, far beyond two decimals. */
        val MATH: MathContext = MathContext.DECIMAL128
    }
}

/**
 * Which invoices may be credited, what is left to credit on them and what a credit note may hold
 * (UC-E03, D51, D52): a normal invoice, pending or registered, without a cancellation nor a
 * replacement under way; each quantity credited once; credit notes within the total of the invoice.
 */
class CreditNoteRules(
    private val invoices: InvoiceRepository,
    private val cancellations: InvoiceCancellationRepository,
    private val drafts: SaleDraftRepository,
    private val calculator: CreditLineCalculator = CreditLineCalculator(),
) {
    /**
     * @param id an invoice.
     * @return why it cannot be credited now, or null.
     */
    suspend fun problem(id: InvoiceId): CreditProblem? {
        val invoice = invoices.find(id)
        val status = invoices.statusOf(id)
        return when {
            invoice == null || status == null -> CreditProblem.NOT_FOUND
            invoice.content.type != DocumentType.FN -> CreditProblem.NOT_AN_INVOICE
            status is InvoiceStatus.Rejected -> CreditProblem.REJECTED
            status == InvoiceStatus.Cancelled -> CreditProblem.CANCELLED
            cancellations.find(id)?.state == CancellationState.Requested -> CreditProblem.CANCELLING
            replacedBySale(id) -> CreditProblem.REPLACING
            nothingLeft(invoice) -> CreditProblem.FULLY_CREDITED
            else -> null
        }
    }

    /**
     * @param id an invoice.
     * @return the invoice with what is left to credit on it, or null if there is no such invoice.
     */
    suspend fun creditable(id: InvoiceId): Creditable? = invoices.find(id)?.let { creditable(it) }

    /**
     * @param creditable an invoice and what is left to credit on it.
     * @param forms the lines typed, one per item at most.
     * @return the lines checked, with their amounts.
     * @throws IllegalArgumentException if a line names an item that is not on the invoice.
     */
    fun price(creditable: Creditable, forms: List<CreditLineForm>): PricedCredit {
        val lines = forms.map { form ->
            val line = requireNotNull(creditable.lines.firstOrNull { it.line.itemId == form.itemId }) {
                "Item ${form.itemId.value} is not on the invoice"
            }
            priced(line, form)
        }
        val zero = Money.zero(creditable.remainingAmount.currency)
        val total = lines.mapNotNull { it.line?.amounts?.total }.fold(zero, Money::plus)
        return PricedCredit(lines, total, aboveCap = total.amount > creditable.remainingAmount.amount)
    }

    private fun priced(creditable: CreditableLine, form: CreditLineForm): PricedCreditLine {
        val quantity = number(form.quantity)
        val unitPrice = number(form.unitPrice)
        val problems = setOfNotNull(
            when {
                quantity == null -> CreditLineProblem.QUANTITY_INVALID
                quantity > creditable.remaining -> CreditLineProblem.QUANTITY_TOO_HIGH
                else -> null
            },
            when {
                unitPrice == null -> CreditLineProblem.PRICE_INVALID
                unitPrice > creditable.netUnitPrice.amount -> CreditLineProblem.PRICE_TOO_HIGH
                else -> null
            },
        )
        val line = if (problems.isEmpty() && quantity != null && unitPrice != null) {
            val price = Money.of(unitPrice, creditable.netUnitPrice.currency)
            creditable.line.copy(
                quantity = Quantity.of(quantity),
                listUnitPrice = price,
                discount = null,
                amounts = calculator.amounts(creditable.line, Quantity.of(quantity), price),
            )
        } else {
            null
        }
        return PricedCreditLine(form, problems, line)
    }

    /** @return [text] as a number above zero with [SaleLineForm.MAX_DECIMALS] decimals at most, or null. */
    private fun number(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.takeIf { it.signum() > 0 && it.scale() <= SaleLineForm.MAX_DECIMALS }

    private suspend fun creditable(invoice: Invoice): Creditable {
        val notes = invoices.countedCreditNotes(invoice.content.number)
        val credited = notes.flatMap { it.content.lines }
        val lines = invoice.content.lines.map { line ->
            val taken = credited.filter { it.itemId == line.itemId }.sumOf { it.quantity.value }
            CreditableLine(line, calculator.netUnitPrice(line), (line.quantity.value - taken).max(BigDecimal.ZERO))
        }
        val creditedTotal = notes.map { it.content.totals.total }
            .fold(Money.zero(invoice.content.currency), Money::plus)
        return Creditable(invoice, lines, invoice.content.totals.total - creditedTotal)
    }

    private suspend fun nothingLeft(invoice: Invoice): Boolean {
        val creditable = creditable(invoice)
        return creditable.lines.all { it.remaining.signum() == 0 } || creditable.remainingAmount.amount.signum() <= 0
    }

    private suspend fun replacedBySale(id: InvoiceId): Boolean =
        (listOfNotNull(drafts.current()) + drafts.held()).any { it.replacement?.invoiceId == id }
}
