package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import java.time.Instant

/** Identifier of an issued invoice in the local storage. */
@JvmInline
value class InvoiceId(val value: Long)

/**
 * A line of an issued invoice: a copy of what was sold, which the catalogue no longer changes.
 *
 * @property itemId the item sold.
 * @property itemCode `item_code` of the item when it was sold.
 * @property designation `item_designation` when it was sold.
 * @property unit the measurement unit when it was sold.
 * @property stockTracked whether the item was kept in stock when it was sold.
 * @property quantity `item_quantity`.
 * @property listUnitPrice the unit price of the catalogue, before the discount.
 * @property priceMode whether [listUnitPrice] includes the taxes.
 * @property discount the discount given, or null.
 * @property vatRate the VAT rate applied, in percent, or null when no VAT applied.
 * @property amounts the amounts sent to the OBR.
 */
data class InvoiceLine(
    val itemId: ItemId,
    val itemCode: String,
    val designation: String,
    val unit: String,
    val stockTracked: Boolean,
    val quantity: Quantity,
    val listUnitPrice: Money,
    val priceMode: PriceMode,
    val discount: Discount?,
    val vatRate: BigDecimal?,
    val amounts: LineAmounts,
)

/**
 * The customer of an issued invoice: a copy of the customer when the invoice was issued.
 *
 * @property id the customer.
 * @property name `customer_name`.
 * @property tin `customer_TIN`, recognised by the OBR, or null.
 * @property address `customer_address`; may be empty.
 * @property vatPayer `vat_customer_payer`.
 */
data class InvoiceCustomer(
    val id: CustomerId,
    val name: String,
    val tin: String?,
    val address: String,
    val vatPayer: Boolean,
)

/**
 * What an issued invoice is made of. Nothing of it ever changes: an invoice is only corrected by
 * a cancellation or a credit note.
 *
 * @property type `invoice_type`.
 * @property number `invoice_number`, from the sequence of [type].
 * @property identifier `invoice_identifier`.
 * @property issuedAt `invoice_date`, to the second.
 * @property profileVersion the version of the tax profile in force: versions never change, so it
 * is the seller as it was when the invoice was issued.
 * @property environment the EBMS environment whose system identifier is in [identifier].
 * @property customer the customer.
 * @property paymentType `payment_type`.
 * @property currency `invoice_currency`.
 * @property lines the lines, in order.
 * @property issuedBy the user who issued the invoice.
 */
data class InvoiceContent(
    val type: DocumentType,
    val number: String,
    val identifier: String,
    val issuedAt: Instant,
    val profileVersion: Int,
    val environment: EbmsEnvironment,
    val customer: InvoiceCustomer,
    val paymentType: PaymentType,
    val currency: Currency,
    val lines: List<InvoiceLine>,
    val issuedBy: UserId,
) {
    /** The totals of the invoice: the sums of the amounts of its lines. */
    val totals: InvoiceTotals get() = InvoiceTotals.of(lines.map { it.amounts }, currency)
}

/**
 * An issued invoice.
 *
 * @property id identifier in the local storage.
 * @property content what was issued.
 */
data class Invoice(val id: InvoiceId, val content: InvoiceContent)

/**
 * Where an issued invoice stands with the OBR (UC-C08). An invoice is issued [Pending]; a sale
 * that is not issued is not an invoice and has no status.
 *
 * @property code stable name of the status, stored with the invoice.
 */
sealed class InvoiceStatus(val code: String) {
    /** Stored on the device, not acknowledged by the OBR yet. */
    data object Pending : InvoiceStatus("PENDING")

    /** Acknowledged by the OBR. */
    data object Registered : InvoiceStatus("REGISTERED")

    /** Refused by the OBR, with its [message] (`msg`). */
    data class Rejected(val message: String) : InvoiceStatus("REJECTED")

    /** Cancelled at the OBR. */
    data object Cancelled : InvoiceStatus("CANCELLED")

    /**
     * @param next another status.
     * @return whether an invoice may go from this status to [next]: a pending invoice is
     * registered or rejected, a rejected one may be sent again, a registered one may be cancelled.
     * A cancelled invoice never changes.
     */
    fun canBecome(next: InvoiceStatus): Boolean = when (this) {
        Pending -> next is Registered || next is Rejected
        is Rejected -> next is Pending
        Registered -> next is Cancelled
        Cancelled -> false
    }
}

/** Storage of the issued invoices. An invoice is never changed nor deleted; its status is kept apart. */
interface InvoiceRepository {
    /**
     * @param content the invoice to store.
     * @return the stored invoice, [InvoiceStatus.Pending].
     */
    suspend fun create(content: InvoiceContent): Invoice

    /**
     * @param id an identifier.
     * @return the invoice, or null if there is none.
     */
    suspend fun find(id: InvoiceId): Invoice?

    /**
     * @param number a number.
     * @return the invoice with that number, or null.
     */
    suspend fun findByNumber(number: String): Invoice?

    /**
     * @param id an invoice.
     * @return its status, or null if there is no such invoice.
     */
    suspend fun statusOf(id: InvoiceId): InvoiceStatus?

    /**
     * @param id an invoice.
     * @param status its new status.
     * @param at when it changed.
     * @throws IllegalStateException if the invoice may not go to [status] (see [InvoiceStatus.canBecome]).
     */
    suspend fun setStatus(id: InvoiceId, status: InvoiceStatus, at: Instant)

    /** @return how many invoices were issued. */
    suspend fun count(): Int
}
