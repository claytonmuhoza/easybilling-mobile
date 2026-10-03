package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * Saves the numbering of the documents (UC-A06). Once a number was given, the numbering is locked
 * and nothing is saved any more.
 *
 * @param numbering where the numbering is kept.
 * @param auditLog log that records each save.
 * @param transactions makes the numbering and its audit entry a single write.
 */
class SaveNumbering(
    private val numbering: NumberingRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a save. */
    sealed interface Result {
        /** The numbering is stored. */
        data object Saved : Result

        /** A number was already given; nothing was stored. */
        data object Locked : Result

        /** The form breaks rules; nothing was stored. */
        data class Invalid(val problems: Set<NumberingProblem>) : Result
    }

    /**
     * @param form the numbering as typed.
     * @param identifierRoom the characters left for the number in the identifier of the invoice,
     * or null if it is not known yet.
     * @param actor the user who saves it.
     * @return whether the numbering was stored.
     */
    suspend operator fun invoke(form: NumberingForm, identifierRoom: Int?, actor: UserId): Result = transactions.run {
        if (numbering.settings()?.locked == true) return@run Result.Locked
        when (val check = form.check(identifierRoom)) {
            is NumberingCheck.Invalid -> Result.Invalid(check.problems)
            is NumberingCheck.Valid -> {
                numbering.save(check.settings)
                auditLog.record(AuditEvent.NumberingSaved(actor))
                Result.Saved
            }
        }
    }
}
