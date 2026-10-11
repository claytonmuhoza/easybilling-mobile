package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A credit note to issue, as typed.
 *
 * @property invoiceId the invoice to credit.
 * @property lines the lines credited, one per item at most.
 * @property reasonType the kind of reason chosen, or null when none is.
 * @property detail what the user adds to the reason, as typed; may be empty.
 */
data class CreditRequest(
    val invoiceId: InvoiceId,
    val lines: List<CreditLineForm>,
    val reasonType: CreditReasonType?,
    val detail: String,
)

/**
 * Where the issue of a credit note writes.
 *
 * @property invoices where the invoices and the credit notes are kept.
 * @property outbox the send queue.
 * @property auditLog log that records each issue.
 */
class CreditNoteStorage(
    val invoices: InvoiceRepository,
    val outbox: OutboxRepository,
    val auditLog: AuditLog,
)

/**
 * Issues a credit note on an invoice (UC-E03): the number of the sequence of the credit notes, the
 * credit note, its entry in the send queue and the audit entry are written together or not at
 * all. It is queued after the invoice it credits: if that one did not leave yet, both leave in order.
 *
 * @param rules tells what may be credited.
 * @param authority tells who may correct an invoice.
 * @param stamper gives the seller, the number and the identifier.
 * @param storage where the credit note is written.
 * @param transactions makes the issue a single write.
 * @param time source of the date of the credit note.
 */
class IssueCreditNote(
    private val rules: CreditNoteRules,
    private val authority: CorrectionAuthority,
    private val stamper: DocumentStamper,
    private val storage: CreditNoteStorage,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of an issue. */
    sealed interface Result {
        /** [invoice] is the credit note, waiting to be sent to the OBR. */
        data class Issued(val invoice: Invoice) : Result

        /** The invoice cannot be credited now; nothing was issued. */
        data class Refused(val problem: CreditProblem) : Result

        /** The reason breaks a rule; nothing was issued. */
        data class InvalidReason(val problem: ReasonProblem) : Result

        /** The lines cannot be issued as they are (see [PricedCredit.issuable]); nothing was issued. */
        data class Invalid(val credit: PricedCredit) : Result

        /** The configuration is not complete; nothing was issued. */
        data class NotReady(val readiness: BillingReadiness) : Result

        /** The counter reached the last number its digits allow; nothing was issued. */
        data object NumbersExhausted : Result

        /** The number does not fit in the 90 characters of the identifier; nothing was issued. */
        data object IdentifierTooLong : Result
    }

    /** Leaves the transaction without storing anything: the number is given back. */
    private class Aborted(val result: Result) : Exception()

    /**
     * @param request the credit note as typed.
     * @param actor the user who issues it.
     * @param approver the manager who approved it with their PIN, or null when [actor] may.
     * @return the credit note, or why nothing was issued.
     * @throws PermissionDeniedException if neither [actor] nor [approver] may correct an invoice.
     */
    suspend operator fun invoke(request: CreditRequest, actor: UserId, approver: UserId? = null): Result {
        authority.require(actor, approver)
        return try {
            transactions.run { issued(request, actor, approver) }
        } catch (aborted: Aborted) {
            aborted.result
        }
    }

    private suspend fun issued(request: CreditRequest, actor: UserId, approver: UserId?): Result {
        rules.problem(request.invoiceId)?.let { throw Aborted(Result.Refused(it)) }
        val reason = reason(request)
        val creditable = checkNotNull(rules.creditable(request.invoiceId)) { "A creditable invoice exists" }
        val credit = rules.price(creditable, request.lines)
        if (!credit.issuable) throw Aborted(Result.Invalid(credit))
        val seller = seller()

        // The OBR reads the date to the second.
        val issuedAt = time.now().truncatedTo(ChronoUnit.SECONDS)
        val stamp = stamp(seller, issuedAt)
        val origin = creditable.invoice.content
        val note = storage.invoices.create(
            origin.copy(
                type = DocumentType.FA,
                number = stamp.number,
                identifier = stamp.identifier,
                issuedAt = issuedAt,
                profileVersion = seller.profileVersion,
                environment = seller.environment,
                lines = credit.lines.map { checkNotNull(it.line) { "An issuable line has its amounts" } },
                issuedBy = actor,
                cancelledInvoiceRef = null,
                credit = CreditNote(origin.number, reason),
            ),
        )
        storage.outbox.enqueue(OutboxOperation.SendInvoice(note.id), issuedAt)
        storage.auditLog.record(
            AuditEvent.CreditNoteIssued(actor, note.id, stamp.number, creditable.invoice.id, approver),
        )
        return Result.Issued(note)
    }

    private suspend fun seller(): DocumentStamper.Seller = when (val result = stamper.seller()) {
        is DocumentStamper.SellerResult.Ready -> result.seller
        is DocumentStamper.SellerResult.NotReady -> throw Aborted(Result.NotReady(result.readiness))
    }

    private suspend fun stamp(seller: DocumentStamper.Seller, issuedAt: Instant): DocumentStamper.Stamp.Done =
        when (val stamp = stamper.stamp(DocumentType.FA, seller, issuedAt)) {
            is DocumentStamper.Stamp.Done -> stamp
            DocumentStamper.Stamp.NumbersExhausted -> throw Aborted(Result.NumbersExhausted)
            DocumentStamper.Stamp.IdentifierTooLong -> throw Aborted(Result.IdentifierTooLong)
        }

    private fun reason(request: CreditRequest): CreditReason {
        val problem = CreditReason.problem(request.reasonType, request.detail)
        if (problem != null || request.reasonType == null) {
            throw Aborted(Result.InvalidReason(problem ?: ReasonProblem.BLANK))
        }
        return CreditReason(request.reasonType, request.detail.trim())
    }
}
