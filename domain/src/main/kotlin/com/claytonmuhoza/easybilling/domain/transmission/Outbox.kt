package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import java.time.Instant

/** Something to send to the OBR. It is stored before anything is sent (`docs/architecture.md` § 7.6). */
sealed interface OutboxOperation {
    /** Sends the invoice [invoice] (`addInvoice_confirm/`). */
    data class SendInvoice(val invoice: InvoiceId) : OutboxOperation
}

/**
 * An operation waiting in the send queue.
 *
 * @property id position in the queue: operations are sent in that order.
 * @property operation what to send.
 * @property createdAt when it entered the queue.
 */
data class OutboxEntry(val id: Long, val operation: OutboxOperation, val createdAt: Instant)

/** Storage of the send queue. */
interface OutboxRepository {
    /**
     * @param operation what to send.
     * @param createdAt when it enters the queue.
     * @return the stored entry, last of the queue.
     */
    suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry

    /** @return the operations still to send, oldest first. */
    suspend fun pending(): List<OutboxEntry>
}
