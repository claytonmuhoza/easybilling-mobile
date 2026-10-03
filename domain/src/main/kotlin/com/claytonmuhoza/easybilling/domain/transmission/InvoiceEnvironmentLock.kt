package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EnvironmentLock
import com.claytonmuhoza.easybilling.domain.configuration.EnvironmentRefusal
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository

/**
 * Keeps the fiscal documents with the environment they were issued for: the environment does not
 * change while documents wait to be sent, and never goes back to test once a real invoice was
 * issued in production (UC-A04).
 *
 * @param invoices the issued invoices.
 * @param outbox the send queue.
 */
class InvoiceEnvironmentLock(
    private val invoices: InvoiceRepository,
    private val outbox: OutboxRepository,
) : EnvironmentLock {

    override suspend fun refusal(from: EbmsEnvironment, to: EbmsEnvironment): EnvironmentRefusal? = when {
        from == to -> null
        outbox.pending().isNotEmpty() -> EnvironmentRefusal.PENDING_TRANSMISSIONS
        to == EbmsEnvironment.TEST && invoices.countIn(EbmsEnvironment.PRODUCTION) > 0 ->
            EnvironmentRefusal.PRODUCTION_IN_USE
        else -> null
    }
}
