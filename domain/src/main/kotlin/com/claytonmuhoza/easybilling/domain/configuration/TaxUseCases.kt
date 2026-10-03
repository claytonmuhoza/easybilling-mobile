package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * Creates a tax with its first rate (UC-A07).
 *
 * @param taxes where the taxes are kept.
 * @param auditLog log that records each creation.
 * @param time source of the current day.
 * @param transactions makes the tax and its audit entry a single write.
 */
class CreateTax(
    private val taxes: TaxRepository,
    private val auditLog: AuditLog,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a creation. */
    sealed interface Result {
        /** [tax] is stored. */
        data class Created(val tax: TaxDefinition) : Result

        /** The form breaks rules; nothing was stored. */
        data class Invalid(val problems: Set<TaxProblem>) : Result
    }

    /**
     * @param form the tax as typed.
     * @param actor the user who creates it.
     * @return the created tax, or why nothing was stored.
     */
    suspend operator fun invoke(form: TaxForm, actor: UserId): Result =
        when (val check = form.check(BusinessCalendar.today(time))) {
            is TaxCheck.Invalid -> Result.Invalid(check.problems)
            is TaxCheck.Valid -> transactions.run {
                val tax = taxes.create(check.kind, check.name, check.version)
                auditLog.record(AuditEvent.TaxCreated(actor, tax.id, tax.kind))
                Result.Created(tax)
            }
        }
}

/**
 * Schedules a new rate of a tax from a later day (UC-A07). The previous rates stay in force
 * before that day: a legal change never alters the past.
 *
 * @param taxes where the taxes are kept.
 * @param auditLog log that records each new rate.
 * @param time source of the current day.
 * @param transactions makes the rate and its audit entry a single write.
 */
class ScheduleTaxRate(
    private val taxes: TaxRepository,
    private val auditLog: AuditLog,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a new rate. */
    sealed interface Result {
        /** The rate is stored. */
        data object Scheduled : Result

        /** The form breaks rules; nothing was stored. */
        data class Invalid(val problems: Set<TaxProblem>) : Result

        /** There is no tax with that identifier. */
        data object NotFound : Result
    }

    /**
     * @param id the tax.
     * @param form the new rate as typed.
     * @param actor the user who schedules it.
     * @return whether the rate was stored.
     */
    suspend operator fun invoke(id: TaxId, form: TaxRateForm, actor: UserId): Result {
        val tax = taxes.find(id) ?: return Result.NotFound
        return when (val check = form.check(tax.kind, BusinessCalendar.today(time), tax.latest.validFrom)) {
            is TaxRateCheck.Invalid -> Result.Invalid(check.problems)
            is TaxRateCheck.Valid -> transactions.run {
                taxes.addVersion(id, check.version)
                auditLog.record(AuditEvent.TaxRateScheduled(actor, id, check.version.validFrom))
                Result.Scheduled
            }
        }
    }
}

/**
 * Archives a tax so it can no longer be chosen, or brings it back. It is never deleted: past
 * invoices keep what they were computed with.
 *
 * @param taxes where the taxes are kept.
 * @param auditLog log that records each change.
 * @param transactions makes the change and its audit entry a single write.
 */
class ArchiveTax(
    private val taxes: TaxRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change. */
    enum class Result {
        /** The tax is archived or back. */
        Done,

        /** There is no tax with that identifier. */
        NotFound,
    }

    /**
     * @param id the tax.
     * @param archived true to archive it, false to bring it back.
     * @param actor the user who asks for it.
     * @return whether the tax was found.
     */
    suspend operator fun invoke(id: TaxId, archived: Boolean, actor: UserId): Result {
        taxes.find(id) ?: return Result.NotFound
        transactions.run {
            taxes.setArchived(id, archived)
            auditLog.record(AuditEvent.TaxArchived(actor, id, archived))
        }
        return Result.Done
    }
}
