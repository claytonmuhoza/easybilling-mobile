package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The messages of the OBR the send queue reacts to; every other refusal is a rejection. */
object ObrMessages {
    /** The number is already known: an earlier attempt may have succeeded without its answer. */
    const val DUPLICATE_NUMBER = "Une facture avec le même numéro de facture existe déjà."

    /** The same refusal as the server really words it (observed on 5 October 2026). */
    const val DUPLICATE_NUMBER_OBSERVED = "Une facture avec le même numéro existe déjà."

    /**
     * @param message a refusal of the OBR.
     * @return whether it says the number is already known, in either wording.
     */
    fun isDuplicateNumber(message: String): Boolean =
        message == DUPLICATE_NUMBER || message == DUPLICATE_NUMBER_OBSERVED

    /** The clock of the device is ahead of the one of the OBR: to send again later. */
    const val DATE_IN_FUTURE = "La date de facturation fournie est supérieur à la date actuelle."
}

/**
 * The refusals the application gives itself, kept with the status of the document where the
 * refusals of the OBR are. They are in French, as the messages of the OBR.
 */
object LocalRefusals {
    /**
     * A credit note is only sent once the OBR has the invoice it credits (UC-E03): it is not sent
     * when that invoice was rejected.
     */
    const val CREDITED_INVOICE_NOT_REGISTERED =
        "La facture d'origine n'est pas enregistrée à l'OBR : la facture d'avoir n'a pas été envoyée."
}

/**
 * What the send queue reads and writes.
 *
 * @property outbox the queue.
 * @property invoices the invoices and their status.
 * @property receipts the acknowledgements of the OBR.
 * @property profiles the versions of the tax profile the invoices were issued with.
 * @property cancellations the cancellations of the invoices.
 * @property stock the stock movements and their status.
 * @property auditLog log that records each acknowledgement and each rejection.
 * @property transactions makes the end of an operation a single write.
 */
class DispatchStorage(
    val outbox: OutboxRepository,
    val invoices: InvoiceRepository,
    val receipts: ReceiptArchive,
    val profiles: TaxProfileRepository,
    val cancellations: InvoiceCancellationRepository,
    val stock: StockRepository,
    val auditLog: AuditLog,
    val transactions: TransactionRunner,
)

/**
 * How the send queue reaches the OBR.
 *
 * @property target gives the server of the active environment, or null if it is not configured.
 * @property tokens gives the token of each call.
 * @property gateway the fiscal services of the EBMS.
 */
class DispatchConnection(
    val target: suspend () -> LoginTarget?,
    val tokens: TokenProvider,
    val gateway: ObrGateway,
) {
    /**
     * Runs [call] with a token; a refused token is renewed and the call made again, once.
     *
     * @param refused tells whether an answer is a refusal of the token.
     * @param call the call to make with a token.
     * @return what the call gave, or null when no token could be obtained.
     */
    suspend fun <T> withToken(refused: (T) -> Boolean, call: suspend (String) -> T): T? {
        val first = token()?.let { call(it) }
        return if (first != null && refused(first)) {
            tokens.invalidate()
            token()?.let { call(it) }
        } else {
            first
        }
    }

    private suspend fun token(): String? = (tokens.token() as? LoginOutcome.Success)?.token
}

/**
 * What a pass over the queue did.
 *
 * @property registered how many invoices the OBR acknowledged.
 * @property rejected how many invoices the OBR refused.
 * @property remaining how many operations are still in the queue.
 * @property cancelled how many invoices the OBR cancelled.
 * @property declared how many stock movements the OBR accepted.
 */
data class DispatchReport(
    val registered: Int = 0,
    val rejected: Int = 0,
    val remaining: Int = 0,
    val cancelled: Int = 0,
    val declared: Int = 0,
)

/**
 * Sends the operations of the queue to the OBR, in the order they were created (UC-D02). A refused
 * invoice leaves the queue and does not stop the next ones; an operation that could not be
 * delivered stops the pass, so the order is kept, and is tried again later.
 *
 * @param storage what the queue reads and writes.
 * @param connection how the OBR is reached.
 * @param retry how long to wait before an operation is tried again.
 * @param time source of the current time.
 */
class DispatchOutbox(
    private val storage: DispatchStorage,
    private val connection: DispatchConnection,
    private val retry: RetryPolicy,
    private val time: TimeProvider,
) {
    private val mutex = Mutex()
    private val cancellations = CancellationDispatch(storage, connection, time)
    private val movements = StockMovementDispatch(storage, connection, time)

    /** How an operation of the queue was settled, for the report. */
    private enum class Settled { REGISTERED, REJECTED, CANCELLED, DECLARED, DROPPED }

    /** How an attempt ended. */
    private sealed interface Attempt {
        /** The OBR has the document, with [receipt] or, after a lost answer, without it. */
        data class Registered(val receipt: ObrReceipt?) : Attempt

        /** The OBR refused the document for good. */
        data class Rejected(val message: String) : Attempt

        /** Nothing is settled: to try again later. [uncertain] when the OBR may have the document. */
        data class Later(val reason: String, val uncertain: Boolean) : Attempt
    }

    /**
     * Runs one pass over the queue. Only one pass runs at a time: a second call waits for the first.
     *
     * @param force true to try the operations whose wait is not over (UC-D05).
     * @return what the pass did.
     */
    suspend operator fun invoke(force: Boolean = false): DispatchReport = mutex.withLock {
        val settled = mutableListOf<Settled>()
        for (entry in storage.outbox.pending()) {
            settled += settled(entry, force) ?: break
        }
        DispatchReport(
            registered = settled.count { it == Settled.REGISTERED },
            rejected = settled.count { it == Settled.REJECTED },
            remaining = storage.outbox.pending().size,
            cancelled = settled.count { it == Settled.CANCELLED },
            declared = settled.count { it == Settled.DECLARED },
        )
    }

    /**
     * Tries one operation, unless its wait is not over and the pass is not forced.
     *
     * @return the status the OBR gave to the invoice, or null when the operation stays in the queue.
     */
    private suspend fun settled(entry: OutboxEntry, force: Boolean): Settled? {
        val waiting = entry.nextAttemptAt?.isAfter(time.now()) == true
        if (waiting && !force) return null
        return when (val operation = entry.operation) {
            is OutboxOperation.SendInvoice -> sent(entry, operation)
            is OutboxOperation.CancelInvoice -> cancelled(entry, operation)
            is OutboxOperation.SendStockMovement -> moved(entry, operation)
        }
    }

    private suspend fun sent(entry: OutboxEntry, operation: OutboxOperation.SendInvoice): Settled? =
        when (val attempt = attempt(entry, operation)) {
            is Attempt.Registered ->
                Settled.REGISTERED.also { settle(entry, InvoiceStatus.Registered, attempt.receipt) }
            is Attempt.Rejected ->
                Settled.REJECTED.also { settle(entry, InvoiceStatus.Rejected(attempt.message), receipt = null) }
            is Attempt.Later -> null.also { postpone(entry, attempt) }
        }

    private suspend fun cancelled(entry: OutboxEntry, operation: OutboxOperation.CancelInvoice): Settled? =
        when (val outcome = cancellations.send(entry, operation)) {
            CancellationDispatch.Outcome.Cancelled -> Settled.CANCELLED
            CancellationDispatch.Outcome.Dropped -> Settled.DROPPED
            is CancellationDispatch.Outcome.Later ->
                null.also { postpone(entry, Attempt.Later(outcome.reason, uncertain = false)) }
        }

    private suspend fun moved(entry: OutboxEntry, operation: OutboxOperation.SendStockMovement): Settled? =
        when (val outcome = movements.send(entry, operation)) {
            StockMovementDispatch.Outcome.Declared -> Settled.DECLARED
            StockMovementDispatch.Outcome.Dropped -> Settled.DROPPED
            is StockMovementDispatch.Outcome.Later ->
                null.also { postpone(entry, Attempt.Later(outcome.reason, uncertain = false)) }
        }

    private suspend fun attempt(entry: OutboxEntry, operation: OutboxOperation.SendInvoice): Attempt {
        val invoice = storage.invoices.find(operation.invoice)
        val seller = invoice?.let { storage.profiles.find(it.content.profileVersion) }?.profile
        val target = connection.target()
        return when {
            invoice == null || seller == null -> Attempt.Rejected("Invoice ${operation.invoice.value} cannot be read")
            creditedInvoiceMissing(invoice) -> Attempt.Rejected(LocalRefusals.CREDITED_INVOICE_NOT_REGISTERED)
            target == null -> Attempt.Later(LoginOutcome.NotConfigured.code, entry.uncertain)
            target.environment != invoice.content.environment ->
                Attempt.Later("The invoice belongs to ${invoice.content.environment}", entry.uncertain)
            // A request left without an answer: the OBR is asked before anything is sent again.
            entry.uncertain -> lookedUp(target, invoice, rejection = null) { send(target, invoice, seller) }
            else -> send(target, invoice, seller)
        }
    }

    /**
     * @return whether [invoice] is a credit note whose invoice the OBR does not have: rejected, or
     * sent again and waiting behind in the queue.
     */
    private suspend fun creditedInvoiceMissing(invoice: Invoice): Boolean {
        val credit = invoice.content.credit ?: return false
        val status = storage.invoices.findByNumber(credit.invoiceRef)?.let { storage.invoices.statusOf(it.id) }
        return status != InvoiceStatus.Registered && status != InvoiceStatus.Cancelled
    }

    private suspend fun send(target: LoginTarget, invoice: Invoice, seller: TaxProfile): Attempt {
        val outcome = connection.withToken({ it is SendOutcome.TokenRefused }) { token ->
            connection.gateway.addInvoice(target, token, invoice, seller)
        }
        return when (outcome) {
            null -> Attempt.Later("LOGIN", uncertain = false)
            is SendOutcome.Accepted -> Attempt.Registered(outcome.receipt)
            is SendOutcome.NotDelivered -> Attempt.Later(outcome.reason, uncertain = false)
            is SendOutcome.Uncertain -> Attempt.Later(outcome.reason, uncertain = true)
            SendOutcome.TokenRefused -> Attempt.Later("TOKEN_REFUSED", uncertain = false)
            is SendOutcome.Rejected -> when {
                outcome.message == ObrMessages.DATE_IN_FUTURE -> Attempt.Later(outcome.message, uncertain = false)
                // An earlier attempt may have succeeded: only the OBR can tell.
                ObrMessages.isDuplicateNumber(outcome.message) -> lookedUp(target, invoice, outcome.message) {
                    Attempt.Rejected(outcome.message)
                }
                else -> Attempt.Rejected(outcome.message)
            }
        }
    }

    /**
     * Asks the OBR whether it has [invoice]. If it has, the invoice is registered, without an
     * acknowledgement; if it has not, [otherwise] decides.
     */
    private suspend fun lookedUp(
        target: LoginTarget,
        invoice: Invoice,
        rejection: String?,
        otherwise: suspend () -> Attempt,
    ): Attempt {
        val outcome = connection.withToken({ it is LookupOutcome.TokenRefused }) { token ->
            connection.gateway.getInvoice(target, token, invoice.content.identifier)
        }
        return when (outcome) {
            is LookupOutcome.Found -> Attempt.Registered(receipt = null)
            LookupOutcome.NotFound -> otherwise()
            is LookupOutcome.Unavailable -> Attempt.Later(rejection ?: outcome.reason, uncertain = true)
            LookupOutcome.TokenRefused, null -> Attempt.Later(rejection ?: "LOGIN", uncertain = true)
        }
    }

    /** The OBR settled the operation: the invoice takes its status and the operation leaves the queue. */
    private suspend fun settle(entry: OutboxEntry, status: InvoiceStatus, receipt: ObrReceipt?) {
        val invoice = (entry.operation as OutboxOperation.SendInvoice).invoice
        storage.transactions.run {
            val now = time.now()
            if (storage.invoices.statusOf(invoice) != null) {
                storage.invoices.setStatus(invoice, status, now)
                receipt?.let { storage.receipts.archive(invoice, it, now) }
                storage.auditLog.record(
                    when (status) {
                        is InvoiceStatus.Rejected -> AuditEvent.InvoiceRejected(invoice)
                        else -> AuditEvent.InvoiceRegistered(invoice, receipt?.registeredNumber.orEmpty())
                    },
                )
                storage.invoices.find(invoice)?.let { cancellations.afterInvoice(it, status) }
            }
            storage.outbox.remove(entry.id)
        }
    }

    private suspend fun postpone(entry: OutboxEntry, attempt: Attempt.Later) {
        val attempts = entry.attempts + 1
        storage.outbox.update(
            entry.copy(
                attempts = attempts,
                nextAttemptAt = time.now() + retry.delay(attempts),
                uncertain = attempt.uncertain,
                lastError = attempt.reason,
            ),
        )
    }
}
