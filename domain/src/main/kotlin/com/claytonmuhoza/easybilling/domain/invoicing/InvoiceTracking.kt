package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Money
import java.time.Instant
import java.time.LocalDate

/**
 * An issued invoice as the list shows it (UC-C08).
 *
 * @property id the invoice.
 * @property type `invoice_type`.
 * @property number `invoice_number`.
 * @property issuedAt `invoice_date`.
 * @property customerName `customer_name`, as it was when the invoice was issued.
 * @property paymentType `payment_type`.
 * @property total the total of the invoice.
 * @property status where the invoice stands with the OBR.
 */
data class InvoiceSummary(
    val id: InvoiceId,
    val type: DocumentType,
    val number: String,
    val issuedAt: Instant,
    val customerName: String,
    val paymentType: PaymentType,
    val total: Money,
    val status: InvoiceStatus,
)

/**
 * The periods the list of the invoices is filtered by.
 *
 * @property days how many days the period covers, today included, or null for no limit.
 */
enum class InvoicePeriod(val days: Long?) {
    /** Every invoice. */
    ALL(null),

    /** The invoices issued today. */
    TODAY(1),

    /** The invoices issued today or in the six days before. */
    LAST_7_DAYS(7),

    /** The invoices issued today or in the twenty-nine days before. */
    LAST_30_DAYS(30),
    ;

    /**
     * @param now the current time.
     * @return the start of the first day of the period in Bujumbura, or null for no limit.
     */
    fun since(now: Instant): Instant? = days?.let {
        val today = now.atZone(BusinessCalendar.ZONE).toLocalDate()
        today.minusDays(it - 1).atStartOfDay(BusinessCalendar.ZONE).toInstant()
    }
}

/**
 * What the list of the invoices looks for (UC-C08).
 *
 * @property query a part of the number or of the name of the customer; empty for every invoice.
 * @property status the status of the invoices, or null for every status.
 * @property since the moment from which the invoices were issued, or null for no limit.
 * @property type the type of the documents, or null for every type.
 */
data class InvoiceFilter(
    val query: String = "",
    val status: InvoiceStatusKind? = null,
    val since: Instant? = null,
    val type: DocumentType? = null,
)

/** Reads the issued invoices as the list shows them (UC-C08). */
interface InvoiceListing {
    /**
     * @param filter what to look for.
     * @param limit the most invoices to return.
     * @return the invoices that match, the most recently issued first.
     */
    suspend fun search(filter: InvoiceFilter, limit: Int): List<InvoiceSummary>

    /**
     * @param filter what to look for; its status is ignored.
     * @return how many invoices match, for each status that has some.
     */
    suspend fun countByStatus(filter: InvoiceFilter): Map<InvoiceStatusKind, Int>
}

/**
 * The invoices of a day.
 *
 * @property date the day, in Bujumbura.
 * @property invoices the invoices issued that day, in the order they were given.
 * @property total the sum of the invoices that count as sales: the refused and the cancelled
 * ones are left out, and so are the credit notes, which are not sales.
 */
data class InvoiceDay(val date: LocalDate, val invoices: List<InvoiceSummary>, val total: Money)

/**
 * @return these invoices grouped by the day they were issued in Bujumbura, in the order the days
 * first appear: the most recent day first when the invoices are.
 */
fun List<InvoiceSummary>.groupedByDay(): List<InvoiceDay> =
    groupBy { it.issuedAt.atZone(BusinessCalendar.ZONE).toLocalDate() }.map { (date, invoices) ->
        val counted = invoices.filter {
            it.type == DocumentType.FN &&
                it.status.kind != InvoiceStatusKind.REJECTED &&
                it.status.kind != InvoiceStatusKind.CANCELLED
        }
        val zero = Money.zero(invoices.first().total.currency)
        InvoiceDay(date, invoices, counted.map { it.total }.fold(zero, Money::plus))
    }
