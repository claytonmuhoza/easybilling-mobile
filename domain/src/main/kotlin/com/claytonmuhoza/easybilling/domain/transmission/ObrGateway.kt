package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import java.math.BigDecimal
import java.time.Instant

/**
 * The acknowledgement of an invoice by the OBR, kept as the proof it was received (UC-D09).
 *
 * @property registeredNumber `invoice_registered_number`.
 * @property registeredDate `invoice_registered_date`, as the OBR wrote it.
 * @property signature `electronic_signature`, kept as received: it is not checked (decision D03).
 * @property rawResponse the whole body of the response, as received.
 */
data class ObrReceipt(
    val registeredNumber: String,
    val registeredDate: String,
    val signature: String,
    val rawResponse: String,
)

/** What sending something to the OBR gave (`docs/architecture.md` § 7.6). */
sealed interface SendOutcome {
    /** The OBR accepted it and gave [receipt]. */
    data class Accepted(val receipt: ObrReceipt) : SendOutcome

    /** The OBR refused it, with its [message] (`msg`). */
    data class Rejected(val message: String) : SendOutcome

    /** The OBR refused the token: a new one is needed. */
    data object TokenRefused : SendOutcome

    /** The request certainly did not reach the OBR, or its server failed: it can be sent again. */
    data class NotDelivered(val reason: String) : SendOutcome

    /** The request left and no answer came: the OBR may have it or not. */
    data class Uncertain(val reason: String) : SendOutcome
}

/**
 * An invoice as the OBR registered it, read from `getInvoice/` (UC-C09). Only what the
 * specification defines is read, and what an answer does not say stays null.
 *
 * @property number `invoice_number`.
 * @property date `invoice_date`, as the OBR wrote it.
 * @property customerName `customer_name`.
 * @property itemCount how many `invoice_items` the invoice has.
 * @property total the sum of the `item_total_amount` of its items, or null if one cannot be read.
 * @property cancelled `cancelled_invoice`: true for `Y`, false for `N`, null when the answer
 * does not say.
 */
data class ObrInvoiceCopy(
    val number: String,
    val date: String,
    val customerName: String,
    val itemCount: Int,
    val total: BigDecimal?,
    val cancelled: Boolean?,
)

/** What looking for an invoice at the OBR gave (`getInvoice/`). */
sealed interface LookupOutcome {
    /** The OBR has the invoice, as [copy]. */
    data class Found(val copy: ObrInvoiceCopy) : LookupOutcome

    /** The OBR does not know the identifier. */
    data object NotFound : LookupOutcome

    /** The OBR refused the token: a new one is needed. */
    data object TokenRefused : LookupOutcome

    /** No usable answer came: to ask again later. */
    data class Unavailable(val reason: String) : LookupOutcome
}

/** The fiscal services of the EBMS. The domain knows nothing of HTTP nor of JSON. */
interface ObrGateway {
    /**
     * Sends an invoice (`addInvoice_confirm/`).
     *
     * @param target the server to call.
     * @param token a token given by `login/`.
     * @param invoice the invoice.
     * @param seller the tax profile the invoice was issued with.
     * @return what the OBR answered.
     */
    suspend fun addInvoice(target: LoginTarget, token: String, invoice: Invoice, seller: TaxProfile): SendOutcome

    /**
     * Looks for an invoice (`getInvoice/`).
     *
     * @param target the server to call.
     * @param token a token given by `login/`.
     * @param identifier the `invoice_identifier`.
     * @return the invoice as the OBR has it, or why there is none.
     */
    suspend fun getInvoice(target: LoginTarget, token: String, identifier: String): LookupOutcome
}

/** Storage of the acknowledgements of the OBR. It only ever adds: an acknowledgement is a proof. */
interface ReceiptArchive {
    /**
     * @param invoice the invoice the OBR acknowledged.
     * @param receipt the acknowledgement.
     * @param receivedAt when it was received.
     */
    suspend fun archive(invoice: InvoiceId, receipt: ObrReceipt, receivedAt: Instant)

    /**
     * @param invoice an invoice.
     * @return its acknowledgement, or null if there is none.
     */
    suspend fun find(invoice: InvoiceId): ObrReceipt?
}
