package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus

/** What the copy of the OBR may say otherwise than the device. */
enum class InvoiceDifference {
    /** The `invoice_number` is not the number of the invoice. */
    NUMBER,

    /** The sum of the `item_total_amount` is not the total of the invoice. */
    TOTAL,

    /** The OBR has the invoice cancelled and the device has not, or the reverse. */
    CANCELLED,
}

/**
 * Asks the OBR for an invoice and compares its copy with the device (UC-C09). Nothing is changed
 * on the device, whatever the answer: differences are dealt with by the reconciliation.
 *
 * @param invoices the invoices and their status.
 * @param connection how the OBR is reached.
 */
class VerifyInvoice(private val invoices: InvoiceRepository, private val connection: DispatchConnection) {
    /** Outcome of a check. */
    sealed interface Result {
        /** The OBR has the invoice as [copy], which differs from the device by [differences]. */
        data class Found(val copy: ObrInvoiceCopy, val differences: Set<InvoiceDifference>) : Result

        /** The OBR does not know the identifier of the invoice. */
        data object Absent : Result

        /** No usable answer came, because of [reason]: nothing is concluded. */
        data class Unavailable(val reason: String) : Result

        /** The environment the invoice was issued for is not the active one, or is not configured. */
        data object NotConfigured : Result

        /** There is no such invoice on the device. */
        data object UnknownInvoice : Result
    }

    /**
     * @param id the invoice to check.
     * @return what the OBR has.
     */
    suspend operator fun invoke(id: InvoiceId): Result {
        val invoice = invoices.find(id) ?: return Result.UnknownInvoice
        val target = connection.target()?.takeIf { it.environment == invoice.content.environment }
        return if (target == null) Result.NotConfigured else asked(target, invoice)
    }

    private suspend fun asked(target: LoginTarget, invoice: Invoice): Result {
        val outcome = connection.withToken({ it is LookupOutcome.TokenRefused }) { token ->
            connection.gateway.getInvoice(target, token, invoice.content.identifier)
        }
        return when (outcome) {
            is LookupOutcome.Found -> Result.Found(outcome.copy, differences(invoice, outcome.copy))
            LookupOutcome.NotFound -> Result.Absent
            is LookupOutcome.Unavailable -> Result.Unavailable(outcome.reason)
            LookupOutcome.TokenRefused -> Result.Unavailable("TOKEN_REFUSED")
            null -> Result.Unavailable("LOGIN")
        }
    }

    private suspend fun differences(invoice: Invoice, copy: ObrInvoiceCopy): Set<InvoiceDifference> {
        val cancelled = invoices.statusOf(invoice.id) is InvoiceStatus.Cancelled
        return buildSet {
            if (copy.number != invoice.content.number) add(InvoiceDifference.NUMBER)
            if (copy.total != null && copy.total.compareTo(invoice.content.totals.total.amount) != 0) {
                add(InvoiceDifference.TOTAL)
            }
            if (copy.cancelled != null && copy.cancelled != cancelled) add(InvoiceDifference.CANCELLED)
        }
    }
}
