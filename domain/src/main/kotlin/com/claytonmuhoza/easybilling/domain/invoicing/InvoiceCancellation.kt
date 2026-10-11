package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.time.Instant

/** How an invoice is cancelled (D51). */
enum class CancellationKind {
    /** `cancelInvoice/` alone (UC-E01). */
    CANCEL,

    /** A new invoice with `cancelled_invoice_ref`, which cancels the old one at the OBR (UC-E02). */
    REPLACE,
}

/** Where the cancellation of an invoice stands. */
sealed interface CancellationState {
    /** Waiting to be sent, or for the replacement to be accepted. */
    data object Requested : CancellationState

    /** The OBR cancelled the invoice. */
    data object Done : CancellationState

    /** The OBR refused the cancellation or the replacement, with its [message] (`msg`). */
    data class Refused(val message: String) : CancellationState

    /** The invoice was rejected by the OBR before its cancellation left: there is nothing to cancel. */
    data object Abandoned : CancellationState
}

/**
 * The cancellation of an invoice, the last one asked for. The invoice itself never changes.
 *
 * @property invoiceId the invoice to cancel.
 * @property kind how it is cancelled.
 * @property reason why, as typed: `cn_motif` of `cancelInvoice/`.
 * @property requestedBy the user who asked for it.
 * @property approvedBy the manager who approved it with their PIN, or null when the user may.
 * @property requestedAt when it was asked for.
 * @property state where it stands.
 * @property replacement the invoice that replaces it, for [CancellationKind.REPLACE].
 */
data class InvoiceCancellation(
    val invoiceId: InvoiceId,
    val kind: CancellationKind,
    val reason: String,
    val requestedBy: UserId,
    val approvedBy: UserId?,
    val requestedAt: Instant,
    val state: CancellationState,
    val replacement: InvoiceId? = null,
)

/** Storage of the cancellations: one per invoice, the last one asked for. */
interface InvoiceCancellationRepository {
    /** @param cancellation the cancellation, which replaces the one of its invoice. */
    suspend fun save(cancellation: InvoiceCancellation)

    /**
     * @param invoiceId an invoice.
     * @return the last cancellation asked for it, or null.
     */
    suspend fun find(invoiceId: InvoiceId): InvoiceCancellation?
}

/**
 * Why an invoice is to be replaced, carried by the sale that replaces it until it is issued.
 *
 * @property invoiceId the invoice the sale replaces.
 * @property reason why it is replaced.
 * @property approvedBy the manager who approved the replacement with their PIN, or null.
 */
data class Replacement(val invoiceId: InvoiceId, val reason: String, val approvedBy: UserId?)

/** A broken rule of the reason of a cancellation. */
enum class ReasonProblem {
    /** Nothing was typed. */
    BLANK,

    /** Longer than [CancellationReason.MAX_LENGTH]. */
    TOO_LONG,
}

/** The reason of a cancellation (`cn_motif`), required although the server does not ask for it (D51). */
object CancellationReason {
    /** Most characters of `cn_motif` (`docs/architecture.md` § 7.4). */
    const val MAX_LENGTH = 100

    /**
     * @param typed the reason as typed.
     * @return the rule [typed] breaks once trimmed, or null.
     */
    fun problem(typed: String): ReasonProblem? {
        val reason = typed.trim()
        return when {
            reason.isEmpty() -> ReasonProblem.BLANK
            reason.length > MAX_LENGTH -> ReasonProblem.TOO_LONG
            else -> null
        }
    }
}

/** Why an invoice cannot be cancelled or replaced now. */
enum class CancellationProblem {
    /** There is no such invoice. */
    NOT_FOUND,

    /** The OBR rejected it: it does not exist there. */
    REJECTED,

    /** It is already cancelled. */
    CANCELLED,

    /** Its cancellation, or its replacement, waits for the OBR. */
    CANCELLING,

    /** A sale that replaces it is being typed or is on hold. */
    REPLACING,
}

/**
 * Which invoices may be cancelled or replaced (D51): pending or registered, without a cancellation
 * nor a replacement under way.
 */
class CancellationRules(
    private val invoices: InvoiceRepository,
    private val cancellations: InvoiceCancellationRepository,
    private val drafts: SaleDraftRepository,
) {
    /**
     * @param id an invoice.
     * @param replacingDraft the sale that replaces it, which does not count as a replacement under way.
     * @return why it cannot be cancelled now, or null.
     */
    suspend fun problem(id: InvoiceId, replacingDraft: DraftId? = null): CancellationProblem? {
        val status = invoices.statusOf(id)
        return when {
            status == null -> CancellationProblem.NOT_FOUND
            status is InvoiceStatus.Rejected -> CancellationProblem.REJECTED
            status == InvoiceStatus.Cancelled -> CancellationProblem.CANCELLED
            cancellations.find(id)?.state == CancellationState.Requested -> CancellationProblem.CANCELLING
            replacedBySale(id, replacingDraft) -> CancellationProblem.REPLACING
            else -> null
        }
    }

    private suspend fun replacedBySale(id: InvoiceId, except: DraftId?): Boolean =
        (listOfNotNull(drafts.current()) + drafts.held())
            .any { it.id != except && it.replacement?.invoiceId == id }
}

/**
 * Who corrects an invoice: a user allowed to, or any user who sees the invoices with the approval
 * of another user allowed to approve a sensitive action, given with their PIN (UC-H04).
 */
class CorrectionAuthority(
    private val access: AccessControl,
    private val users: UserRepository,
    private val policy: PermissionPolicy,
) {
    /**
     * @param actor the user who asks for the correction.
     * @param approver the user who approved it, or null when [actor] corrects alone.
     * @throws PermissionDeniedException if neither [actor] nor [approver] may correct it.
     */
    suspend fun require(actor: UserId, approver: UserId?) {
        if (approver == null) {
            access.require(actor, Permission.CORRECT_INVOICES)
            return
        }
        access.require(actor, Permission.VIEW_INVOICES)
        val user = users.find(approver)
        val approves = user != null && user.id != actor && user.active &&
            policy.allows(user.roles, Permission.AUTHORIZE_SENSITIVE_ACTION)
        if (!approves) throw PermissionDeniedException(approver, Permission.AUTHORIZE_SENSITIVE_ACTION)
    }
}

/**
 * Cancels an invoice without replacing it (UC-E01, E07). The cancellation is queued after the
 * invoice: if the invoice did not leave yet, both leave in order.
 */
class CancelInvoice(
    private val rules: CancellationRules,
    private val authority: CorrectionAuthority,
    private val cancellations: InvoiceCancellationRepository,
    private val outbox: OutboxRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of a request. */
    sealed interface Result {
        /** The cancellation waits in the send queue. */
        data object Requested : Result

        /** The reason breaks a rule; nothing was asked for. */
        data class InvalidReason(val problem: ReasonProblem) : Result

        /** The invoice cannot be cancelled now; nothing was asked for. */
        data class Refused(val problem: CancellationProblem) : Result
    }

    /**
     * @param id the invoice to cancel.
     * @param reason why, as typed.
     * @param actor the user who asks for it.
     * @param approver the manager who approved it with their PIN, or null when [actor] may.
     * @return whether the cancellation was asked for.
     * @throws PermissionDeniedException if neither [actor] nor [approver] may correct an invoice.
     */
    suspend operator fun invoke(id: InvoiceId, reason: String, actor: UserId, approver: UserId? = null): Result {
        authority.require(actor, approver)
        val reasonProblem = CancellationReason.problem(reason)
        return transactions.run {
            val problem = rules.problem(id)
            when {
                reasonProblem != null -> Result.InvalidReason(reasonProblem)
                problem != null -> Result.Refused(problem)
                else -> requested(id, reason.trim(), actor, approver)
            }
        }
    }

    private suspend fun requested(id: InvoiceId, reason: String, actor: UserId, approver: UserId?): Result {
        val now = time.now()
        cancellations.save(
            InvoiceCancellation(id, CancellationKind.CANCEL, reason, actor, approver, now, CancellationState.Requested),
        )
        outbox.enqueue(OutboxOperation.CancelInvoice(id), now)
        auditLog.record(AuditEvent.InvoiceCancellationRequested(actor, id, approver))
        return Result.Requested
    }
}

/**
 * Starts the replacement of an invoice (UC-E02): a new sale with its lines, which carries the
 * [Replacement] until it is issued. Nothing is sent before: the issued replacement cancels the
 * invoice at the OBR.
 */
class ReplaceInvoice(
    private val rules: CancellationRules,
    private val authority: CorrectionAuthority,
    private val redoSale: RedoSale,
    private val auditLog: AuditLog,
) {
    /** Outcome of a start. */
    sealed interface Result {
        /** The replacing sale is the one being typed; [skippedLines] lines were left out, their item being archived. */
        data class Started(val skippedLines: Int) : Result

        /** The reason breaks a rule; nothing was started. */
        data class InvalidReason(val problem: ReasonProblem) : Result

        /** The invoice cannot be replaced now; nothing was started. */
        data class Refused(val problem: CancellationProblem) : Result
    }

    /**
     * @param id the invoice to replace.
     * @param reason why, as typed.
     * @param actor the user who asks for it.
     * @param approver the manager who approved it with their PIN, or null when [actor] may.
     * @return whether the replacing sale was started.
     * @throws PermissionDeniedException if neither [actor] nor [approver] may correct an invoice.
     */
    suspend operator fun invoke(id: InvoiceId, reason: String, actor: UserId, approver: UserId? = null): Result {
        authority.require(actor, approver)
        val reasonProblem = CancellationReason.problem(reason)
        val problem = rules.problem(id)
        return when {
            reasonProblem != null -> Result.InvalidReason(reasonProblem)
            problem != null -> Result.Refused(problem)
            else -> started(id, reason.trim(), actor, approver)
        }
    }

    private suspend fun started(id: InvoiceId, reason: String, actor: UserId, approver: UserId?): Result =
        when (val redone = redoSale(id, Replacement(id, reason, approver))) {
            is RedoSale.Result.Started -> {
                auditLog.record(AuditEvent.InvoiceReplacementStarted(actor, id, approver))
                Result.Started(redone.skippedLines)
            }
            RedoSale.Result.NotFound -> Result.Refused(CancellationProblem.NOT_FOUND)
        }
}
