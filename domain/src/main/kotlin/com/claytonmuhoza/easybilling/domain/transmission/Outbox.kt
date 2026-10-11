package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import java.time.Duration
import java.time.Instant

/** Something to send to the OBR. It is stored before anything is sent (`docs/architecture.md` § 7.6). */
sealed interface OutboxOperation {
    /** The invoice the operation sends or cancels. */
    val invoice: InvoiceId

    /** Sends the invoice [invoice] (`addInvoice_confirm/`). */
    data class SendInvoice(override val invoice: InvoiceId) : OutboxOperation

    /** Cancels the invoice [invoice] at the OBR (`cancelInvoice/`), with the reason recorded for it. */
    data class CancelInvoice(override val invoice: InvoiceId) : OutboxOperation
}

/**
 * An operation waiting in the send queue.
 *
 * @property id position in the queue: operations are sent in that order.
 * @property operation what to send.
 * @property createdAt when it entered the queue.
 * @property attempts how many times it was tried.
 * @property nextAttemptAt before when it is not tried again, or null to try at once.
 * @property uncertain whether a request left without an answer: the OBR is asked whether it has
 * the document before anything is sent again.
 * @property lastError why the last attempt did not end; empty before the first one.
 */
data class OutboxEntry(
    val id: Long,
    val operation: OutboxOperation,
    val createdAt: Instant,
    val attempts: Int = 0,
    val nextAttemptAt: Instant? = null,
    val uncertain: Boolean = false,
    val lastError: String = "",
)

/** Storage of the send queue. An operation leaves the queue once the OBR accepted or refused it. */
interface OutboxRepository {
    /**
     * @param operation what to send.
     * @param createdAt when it enters the queue.
     * @return the stored entry, last of the queue.
     */
    suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry

    /** @return the operations still to send, oldest first. */
    suspend fun pending(): List<OutboxEntry>

    /** @param entry the entry, which replaces the stored one with the same identifier. */
    suspend fun update(entry: OutboxEntry)

    /** @param id the entry that leaves the queue. */
    suspend fun remove(id: Long)
}

/** How long to wait before an operation is tried again. */
fun interface RetryPolicy {
    /**
     * @param attempts how many times the operation was tried, one at least.
     * @return how long to wait before the next attempt.
     */
    fun delay(attempts: Int): Duration
}

/**
 * The wait doubles at each attempt, from 30 seconds to 30 minutes (UC-D02): 30 s, 1 min, 2 min,
 * 4 min, 8 min, 16 min, then 30 min.
 */
object ExponentialRetry : RetryPolicy {
    private val FIRST: Duration = Duration.ofSeconds(30)
    private val LONGEST: Duration = Duration.ofMinutes(30)

    /** Doubling beyond this many times would overflow, and is far beyond [LONGEST] anyway. */
    private const val MAX_DOUBLINGS = 10

    override fun delay(attempts: Int): Duration {
        val doublings = (attempts - 1).coerceIn(0, MAX_DOUBLINGS)
        return minOf(FIRST.multipliedBy(1L shl doublings), LONGEST)
    }
}
