package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository

/**
 * Puts a refused invoice back in the send queue once the tax profile is corrected (UC-D04). The
 * invoice is sent with the tax profile in force now, and its identifier takes the NIF of that
 * profile; its number, its date, its customer, its lines and its amounts do not change.
 *
 * @param invoices the invoices and their status.
 * @param profiles where the tax profile is kept.
 * @param outbox the send queue.
 * @param auditLog log that records each resend.
 * @param transactions makes the resend a single write.
 * @param time source of the time of the resend.
 */
class ResendRejectedInvoice(
    private val access: AccessControl,
    private val invoices: InvoiceRepository,
    private val profiles: TaxProfileRepository,
    private val outbox: OutboxRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a resend. */
    sealed interface Result {
        /** [invoice] waits in the send queue again. */
        data class Resent(val invoice: Invoice) : Result

        /** There is no such invoice. */
        data object NotFound : Result

        /** The invoice is not refused: there is nothing to send again. */
        data object NotRejected : Result

        /** With the NIF of the tax profile the identifier is longer than the OBR accepts. */
        data object IdentifierTooLong : Result
    }

    /**
     * @param id the refused invoice.
     * @param actor the user who sends it again.
     * @return the invoice as it will be sent, or why nothing changed.
     * @throws com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException if [actor] is not allowed to.
     */
    suspend operator fun invoke(id: InvoiceId, actor: UserId): Result {
        access.require(actor, Permission.RESOLVE_INVOICES)
        return allowed(id, actor)
    }

    private suspend fun allowed(id: InvoiceId, actor: UserId): Result = transactions.run {
        val invoice = invoices.find(id) ?: return@run Result.NotFound
        if (invoices.statusOf(id) !is InvoiceStatus.Rejected) return@run Result.NotRejected
        val resent = withCurrentSeller(invoice) ?: return@run Result.IdentifierTooLong

        val now = time.now()
        invoices.setStatus(id, InvoiceStatus.Pending, now)
        outbox.enqueue(OutboxOperation.SendInvoice(id), now)
        auditLog.record(AuditEvent.InvoiceResent(actor, id, resent.content.profileVersion))
        Result.Resent(resent)
    }

    /** @return [invoice] with the tax profile in force, or null if its identifier no longer fits. */
    private suspend fun withCurrentSeller(invoice: Invoice): Invoice? {
        val profile = profiles.current()?.takeIf { it.number != invoice.content.profileVersion } ?: return invoice
        return InvoiceIdentifier.withTin(invoice.content.identifier, profile.profile.tin)
            ?.let { invoices.replaceSeller(invoice.id, profile.number, it) }
    }
}

/**
 * Starts the sale of an invoice over (UC-D04, refusal for its content): a new sale with the same
 * customer, lines, discounts and payment, to correct and issue under a new number. The invoice
 * itself does not change. The sale being typed is put on hold.
 *
 * The amounts typed for the taxes computed as a free amount are not copied: they are typed again.
 *
 * @param invoices the issued invoices.
 * @param items the catalogue: an archived item is left out.
 * @param customers the customers: an archived customer is replaced by the counter customer.
 * @param drafts where the sales that are not issued are kept.
 * @param transactions makes the start a single write.
 * @param time source of the time of the new sale.
 */
class RedoSale(
    private val invoices: InvoiceRepository,
    private val items: ItemRepository,
    private val customers: CustomerRepository,
    private val drafts: SaleDraftRepository,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a start. */
    sealed interface Result {
        /** The sale is the one being typed; [skippedLines] lines were left out, their item being archived. */
        data class Started(val skippedLines: Int) : Result

        /** There is no such invoice. */
        data object NotFound : Result
    }

    /**
     * @param id the invoice whose sale is started over.
     * @param replacement what the new sale replaces once issued, or null for a new sale alone.
     * @return what was started.
     */
    suspend operator fun invoke(id: InvoiceId, replacement: Replacement? = null): Result = transactions.run {
        val content = invoices.find(id)?.content ?: return@run Result.NotFound
        val lines = content.lines.filter { items.find(it.itemId)?.archived == false }
        val customer = customers.find(content.customer.id)?.takeIf { !it.archived && !it.counter }

        val now = time.now()
        val typed = drafts.current()
        when {
            typed == null -> Unit
            typed.lines.isEmpty() -> drafts.delete(typed.id)
            else -> drafts.save(typed.copy(held = true, updatedAt = now))
        }
        drafts.save(
            drafts.create(now).copy(
                customerId = customer?.id,
                paymentType = content.paymentType,
                lines = lines.map { DraftLine(it.itemId, it.quantity, it.discount) },
                replacement = replacement,
            ),
        )
        Result.Started(skippedLines = content.lines.size - lines.size)
    }
}
