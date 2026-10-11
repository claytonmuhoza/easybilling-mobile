package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationState
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellation
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider

/**
 * The cancellations in the send queue (UC-E01, E02, E07). A cancellation leaves after its
 * invoice; a replacement cancels the invoice it replaces once the OBR accepts it (§ 7.28).
 *
 * @param storage what the queue reads and writes.
 * @param connection how the OBR is reached.
 * @param time source of the current time.
 */
internal class CancellationDispatch(
    private val storage: DispatchStorage,
    private val connection: DispatchConnection,
    private val time: TimeProvider,
) {
    /** How sending a cancellation ended. */
    sealed interface Outcome {
        /** The OBR cancelled the invoice. */
        data object Cancelled : Outcome

        /** The operation left the queue without cancelling anything: refused, or nothing to cancel. */
        data object Dropped : Outcome

        /** Nothing is settled: to try again later. */
        data class Later(val reason: String) : Outcome
    }

    /**
     * Sends the cancellation of [operation], unless the invoice it cancels makes it pointless.
     *
     * @param entry the entry of the queue.
     * @param operation the cancellation.
     * @return how it ended; the entry leaves the queue unless it is [Outcome.Later].
     */
    suspend fun send(entry: OutboxEntry, operation: OutboxOperation.CancelInvoice): Outcome {
        val invoice = storage.invoices.find(operation.invoice)
        val cancellation = storage.cancellations.find(operation.invoice)
        val status = storage.invoices.statusOf(operation.invoice)
        return when {
            invoice == null || cancellation == null || status == InvoiceStatus.Cancelled -> drop(entry)
            status is InvoiceStatus.Rejected -> abandon(entry, cancellation)
            // Kept by the order of the queue: the invoice is settled before its cancellation.
            status == InvoiceStatus.Pending -> Outcome.Later("INVOICE_PENDING")
            else -> call(entry, invoice, cancellation)
        }
    }

    /**
     * Applies what the OBR said of an invoice to the invoice it replaces, if any: accepted, the
     * replaced invoice is cancelled; refused, its replacement is refused.
     *
     * @param invoice an invoice the OBR just settled.
     * @param status what the OBR said of it.
     */
    suspend fun afterInvoice(invoice: Invoice, status: InvoiceStatus) {
        val replacedNumber = invoice.content.cancelledInvoiceRef ?: return
        val replaced = storage.invoices.findByNumber(replacedNumber) ?: return
        val cancellation = storage.cancellations.find(replaced.id)?.takeIf { it.replacement == invoice.id }
        when {
            cancellation == null -> Unit
            status is InvoiceStatus.Rejected -> refused(cancellation, status.message)
            storage.invoices.statusOf(replaced.id) == InvoiceStatus.Registered -> cancelled(cancellation)
        }
    }

    private suspend fun call(entry: OutboxEntry, invoice: Invoice, cancellation: InvoiceCancellation): Outcome {
        val target = connection.target()
        return when {
            target == null -> Outcome.Later(LoginOutcome.NotConfigured.code)
            target.environment != invoice.content.environment ->
                Outcome.Later("The invoice belongs to ${invoice.content.environment}")
            else -> callTo(target, entry, invoice, cancellation)
        }
    }

    private suspend fun callTo(
        target: LoginTarget,
        entry: OutboxEntry,
        invoice: Invoice,
        cancellation: InvoiceCancellation,
    ): Outcome {
        val outcome = connection.withToken({ it is CancelOutcome.TokenRefused }) { token ->
            connection.gateway.cancelInvoice(target, token, invoice.content.identifier, cancellation.reason)
        }
        return when (outcome) {
            CancelOutcome.Cancelled -> {
                settle(entry) { cancelled(cancellation) }
                Outcome.Cancelled
            }
            is CancelOutcome.Rejected -> {
                settle(entry) { refused(cancellation, outcome.message) }
                Outcome.Dropped
            }
            null -> Outcome.Later("LOGIN")
            CancelOutcome.TokenRefused -> Outcome.Later("TOKEN_REFUSED")
            is CancelOutcome.NotDelivered -> Outcome.Later(outcome.reason)
            // Sending it again is safe: the OBR answers a second cancellation with a success.
            is CancelOutcome.Uncertain -> Outcome.Later(outcome.reason)
        }
    }

    private suspend fun cancelled(cancellation: InvoiceCancellation) {
        val now = time.now()
        storage.invoices.setStatus(cancellation.invoiceId, InvoiceStatus.Cancelled, now)
        // What the invoice took comes back into the stock (UC-E01).
        storage.invoices.find(cancellation.invoiceId)
            ?.let { storage.invoiceStock.restore(it, cancellation.requestedBy, now) }
        storage.cancellations.save(cancellation.copy(state = CancellationState.Done))
        storage.auditLog.record(AuditEvent.InvoiceCancelled(cancellation.invoiceId))
    }

    private suspend fun refused(cancellation: InvoiceCancellation, message: String) {
        storage.cancellations.save(cancellation.copy(state = CancellationState.Refused(message)))
        storage.auditLog.record(AuditEvent.InvoiceCancellationRefused(cancellation.invoiceId, message))
    }

    private suspend fun abandon(entry: OutboxEntry, cancellation: InvoiceCancellation): Outcome {
        settle(entry) {
            storage.cancellations.save(cancellation.copy(state = CancellationState.Abandoned))
            storage.auditLog.record(AuditEvent.InvoiceCancellationAbandoned(cancellation.invoiceId))
        }
        return Outcome.Dropped
    }

    private suspend fun drop(entry: OutboxEntry): Outcome {
        storage.outbox.remove(entry.id)
        return Outcome.Dropped
    }

    /** Runs [change] and takes [entry] out of the queue, in one write. */
    private suspend fun settle(entry: OutboxEntry, change: suspend () -> Unit) {
        storage.transactions.run {
            change()
            storage.outbox.remove(entry.id)
        }
    }
}
