package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.temporal.ChronoUnit

/** Outcome of [CreateCustomer] and [UpdateCustomer]. */
sealed interface CustomerSaveResult {
    /**
     * [customer] is stored.
     *
     * @property restored whether an archived customer with the same TIN was brought back instead
     * of a new one being created.
     */
    data class Saved(val customer: Customer, val restored: Boolean = false) : CustomerSaveResult

    /** The form breaks rules; nothing was stored. */
    data class Invalid(val problems: Set<CustomerProblem>) : CustomerSaveResult

    /** The OBR does not know the TIN; nothing was stored. */
    data object TinUnknown : CustomerSaveResult

    /**
     * The TIN could not be checked, so nothing was stored: to try again.
     *
     * @property failure why.
     * @property message the message of the OBR, when it gave one.
     */
    data class NotVerified(val failure: TinCheckFailure, val message: String) : CustomerSaveResult

    /** There is no customer with that identifier. */
    data object NotFound : CustomerSaveResult
}

/** What checking the TIN of a draft gave: the TIN to store, or why nothing is stored. */
private sealed interface TinOutcome {
    data class Settled(val tin: VerifiedTin?) : TinOutcome

    data class Stopped(val result: CustomerSaveResult) : TinOutcome
}

private suspend fun VerifyTin.outcome(tin: String, time: TimeProvider): TinOutcome = when (val check = this(tin)) {
    is TinCheck.Known ->
        TinOutcome.Settled(VerifiedTin(tin, check.name, time.now().truncatedTo(ChronoUnit.MILLIS)))
    TinCheck.Unknown -> TinOutcome.Stopped(CustomerSaveResult.TinUnknown)
    is TinCheck.Failed -> TinOutcome.Stopped(CustomerSaveResult.NotVerified(check.failure, check.message))
}

private val TIN_TAKEN = CustomerSaveResult.Invalid(setOf(CustomerProblem.TIN_TAKEN))

/**
 * Creates a customer (UC-B05). A TIN is checked with the OBR before anything is stored (UC-B06):
 * without an answer of the OBR, a customer with a TIN is not created.
 *
 * @param customers where the customers are kept.
 * @param verifyTin the check of a TIN with the OBR.
 * @param auditLog log that records each creation.
 * @param transactions makes the customer and its audit entry a single write.
 * @param time source of the time of the check.
 */
class CreateCustomer(
    private val customers: CustomerRepository,
    private val verifyTin: VerifyTin,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /**
     * @param form the customer as typed.
     * @param actor the user who creates it.
     * @return the stored customer, or why nothing was stored. When an archived customer already
     * has the TIN, it is brought back with the typed details instead.
     */
    suspend operator fun invoke(form: CustomerForm, actor: UserId): CustomerSaveResult =
        when (val check = form.check()) {
            is CustomerCheck.Invalid -> CustomerSaveResult.Invalid(check.problems)
            is CustomerCheck.Valid -> saved(check.draft, actor)
        }

    private suspend fun saved(draft: CustomerDraft, actor: UserId): CustomerSaveResult {
        val tin = draft.tin ?: return transactions.run { created(draft.toDetails(null), actor) }
        val holder = customers.findByTin(tin)
        return when {
            holder == null -> verified(tin, draft, actor)
            holder.archived -> restored(holder, draft, actor)
            else -> TIN_TAKEN
        }
    }

    private suspend fun verified(tin: String, draft: CustomerDraft, actor: UserId): CustomerSaveResult =
        when (val outcome = verifyTin.outcome(tin, time)) {
            is TinOutcome.Stopped -> outcome.result
            is TinOutcome.Settled -> transactions.run {
                // The check left the storage free for a while: another customer may have taken the TIN.
                if (customers.findByTin(tin) != null) TIN_TAKEN else created(draft.toDetails(outcome.tin), actor)
            }
        }

    private suspend fun created(details: CustomerDetails, actor: UserId): CustomerSaveResult {
        val customer = customers.create(details)
        auditLog.record(AuditEvent.CustomerCreated(actor, customer.id))
        return CustomerSaveResult.Saved(customer)
    }

    private suspend fun restored(holder: Customer, draft: CustomerDraft, actor: UserId) = transactions.run {
        val details = draft.toDetails(holder.details.tin)
        customers.update(holder.id, details)
        customers.setArchived(holder.id, false)
        auditLog.record(AuditEvent.CustomerUpdated(actor, holder.id))
        auditLog.record(AuditEvent.CustomerArchived(actor, holder.id, false))
        CustomerSaveResult.Saved(holder.copy(details = details, archived = false), restored = true)
    }
}

/**
 * Changes a customer (UC-B05). A new TIN is checked with the OBR first; a TIN that does not change
 * is not checked again. Only the name of the counter customer changes.
 *
 * @param customers where the customers are kept.
 * @param verifyTin the check of a TIN with the OBR.
 * @param auditLog log that records each change.
 * @param transactions makes the change and its audit entry a single write.
 * @param time source of the time of the check.
 */
class UpdateCustomer(
    private val customers: CustomerRepository,
    private val verifyTin: VerifyTin,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /**
     * @param id the customer.
     * @param form the customer as typed.
     * @param actor the user who changes it.
     * @return the stored customer, or why nothing was stored.
     */
    suspend operator fun invoke(id: CustomerId, form: CustomerForm, actor: UserId): CustomerSaveResult {
        val existing = customers.find(id) ?: return CustomerSaveResult.NotFound
        return when (val check = form.check(counter = existing.counter)) {
            is CustomerCheck.Invalid -> CustomerSaveResult.Invalid(check.problems)
            is CustomerCheck.Valid -> when (val outcome = tinOf(existing, check.draft)) {
                is TinOutcome.Stopped -> outcome.result
                is TinOutcome.Settled -> stored(existing, check.draft.toDetails(outcome.tin), actor)
            }
        }
    }

    /** @return the TIN the customer will carry: none, the one it has, or a new one the OBR recognised. */
    private suspend fun tinOf(existing: Customer, draft: CustomerDraft): TinOutcome {
        val current = existing.details.tin
        return when {
            draft.tin == null -> TinOutcome.Settled(null)
            draft.tin == current?.number -> TinOutcome.Settled(current)
            customers.findByTin(draft.tin) != null -> TinOutcome.Stopped(TIN_TAKEN)
            else -> verifyTin.outcome(draft.tin, time)
        }
    }

    private suspend fun stored(existing: Customer, details: CustomerDetails, actor: UserId) = transactions.run {
        // The check left the storage free for a while: another customer may have taken the TIN.
        val holder = details.tin?.let { customers.findByTin(it.number) }
        if (holder != null && holder.id != existing.id) {
            TIN_TAKEN
        } else {
            customers.update(existing.id, details)
            auditLog.record(AuditEvent.CustomerUpdated(actor, existing.id))
            CustomerSaveResult.Saved(existing.copy(details = details))
        }
    }
}

/**
 * Archives a customer so it is no longer proposed at the sale, or brings it back. It is never
 * deleted: the invoices keep referring to it.
 *
 * @param customers where the customers are kept.
 * @param auditLog log that records each change.
 * @param transactions makes the change and its audit entry a single write.
 */
class ArchiveCustomer(
    private val customers: CustomerRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change. */
    enum class Result {
        /** The customer is archived or back. */
        Done,

        /** There is no customer with that identifier. */
        NotFound,

        /** The counter customer is never archived. */
        CounterProtected,
    }

    /**
     * @param id the customer.
     * @param archived true to archive it, false to bring it back.
     * @param actor the user who asks for it.
     * @return whether the change was made.
     */
    suspend operator fun invoke(id: CustomerId, archived: Boolean, actor: UserId): Result = transactions.run {
        val customer = customers.find(id) ?: return@run Result.NotFound
        if (customer.counter) return@run Result.CounterProtected
        customers.setArchived(id, archived)
        auditLog.record(AuditEvent.CustomerArchived(actor, id, archived))
        Result.Done
    }
}
