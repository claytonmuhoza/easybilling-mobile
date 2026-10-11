package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant

/** Tells whether the device has a network: the send queue starts again as soon as it comes back. */
interface NetworkMonitor {
    /** Whether a network is available now. */
    val online: StateFlow<Boolean>
}

/** Plans the next pass over the send queue for when the application is closed. */
interface TransmissionScheduler {
    /**
     * @param delay how long to wait before the pass; it only runs once a network is available.
     * @param afterRunningPass true when asked by the background pass itself: the next one is then
     * chained after it instead of replacing it.
     */
    fun schedule(delay: Duration, afterRunningPass: Boolean = false)

    /** Forgets the planned pass: the queue is empty. */
    fun cancel()
}

/** How worrying the state of the transmission is (UC-D03). */
enum class TransmissionLevel {
    /** Nothing waits for long and nothing was refused. */
    OK,

    /** A document has been waiting for [TransmissionStatus.WARNING_AFTER] or more. */
    WARNING,

    /** A document has been waiting for [TransmissionStatus.ALERT_AFTER] or more, or one was refused. */
    ALERT,
}

/**
 * A document of the send queue, with the invoice it sends.
 *
 * @property entry the entry of the queue.
 * @property invoice the invoice, or null if it can no longer be read.
 */
data class QueuedDocument(val entry: OutboxEntry, val invoice: Invoice?)

/**
 * A refused invoice, to be dealt with.
 *
 * @property invoice the invoice.
 * @property message the message of the OBR.
 */
data class RejectedDocument(val invoice: Invoice, val message: String)

/**
 * Where the transmission to the OBR stands (UC-D03).
 *
 * @property queue the documents still to send, in the order they will be sent.
 * @property rejected the invoices the OBR refused, the most recent first.
 * @property sentToday how many invoices the OBR acknowledged since the start of the day in Bujumbura.
 * @property readAt when this was read: the ages are counted from it.
 */
data class TransmissionStatus(
    val queue: List<QueuedDocument> = emptyList(),
    val rejected: List<RejectedDocument> = emptyList(),
    val sentToday: Int = 0,
    val readAt: Instant,
) {
    /** How long the oldest document of the queue has been waiting, or null when nothing waits. */
    val oldestWait: Duration? get() = queue.firstOrNull()?.let { Duration.between(it.entry.createdAt, readAt) }

    /** When the queue is tried next, or null when nothing waits or the next try is due. */
    val nextAttemptAt: Instant? get() = queue.firstOrNull()?.entry?.nextAttemptAt

    /** How worrying this is: a refusal or a day of waiting is an alert, an hour of waiting a warning. */
    val level: TransmissionLevel
        get() {
            val wait = oldestWait ?: Duration.ZERO
            return when {
                rejected.isNotEmpty() || wait >= ALERT_AFTER -> TransmissionLevel.ALERT
                wait >= WARNING_AFTER -> TransmissionLevel.WARNING
                else -> TransmissionLevel.OK
            }
        }

    /** The thresholds of the levels: internal rules, the specification gives none. */
    companion object {
        /** Wait from which the state is a warning. */
        val WARNING_AFTER: Duration = Duration.ofHours(1)

        /** Wait from which the state is an alert. */
        val ALERT_AFTER: Duration = Duration.ofHours(24)
    }
}

/**
 * Reads where the transmission to the OBR stands (UC-D03).
 *
 * @param outbox the send queue.
 * @param invoices the invoices and their status.
 * @param time source of the current time.
 */
class ReadTransmissionStatus(
    private val outbox: OutboxRepository,
    private val invoices: InvoiceRepository,
    private val time: TimeProvider,
) {
    /** @return the queue, the refused invoices and how many invoices were acknowledged today. */
    suspend operator fun invoke(): TransmissionStatus {
        val now = time.now()
        val startOfDay = now.atZone(BusinessCalendar.ZONE).toLocalDate().atStartOfDay(BusinessCalendar.ZONE).toInstant()
        return TransmissionStatus(
            queue = outbox.pending().map { entry ->
                QueuedDocument(entry, invoices.find(entry.operation.invoice))
            },
            rejected = invoices.rejected(),
            sentToday = invoices.countRegisteredSince(startOfDay),
            readAt = now,
        )
    }
}
