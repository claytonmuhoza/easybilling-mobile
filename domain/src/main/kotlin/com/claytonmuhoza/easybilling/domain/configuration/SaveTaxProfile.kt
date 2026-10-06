package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.temporal.ChronoUnit

/**
 * Saves the tax profile (UC-A02) as a new version, with its audit entry, in one transaction.
 *
 * @param profiles where the versions are kept.
 * @param auditLog log that records every saved version.
 * @param time source of the saving time.
 * @param transactions makes the version and its audit entry a single write.
 */
class SaveTaxProfile(
    private val access: AccessControl,
    private val profiles: TaxProfileRepository,
    private val auditLog: AuditLog,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a save. */
    sealed interface Result {
        /** [version] is the new current version. */
        data class Saved(val version: TaxProfileVersion) : Result

        /** The profile is the same as the current [version]: no version was created. */
        data class Unchanged(val version: TaxProfileVersion) : Result

        /** The form breaks rules; nothing was saved. */
        data class Invalid(
            val problems: Map<TaxProfileField, FieldProblem>,
            val missingChoices: Set<TaxProfileChoice>,
        ) : Result
    }

    /**
     * @param form the profile typed by the user.
     * @param actor the user who saves it.
     * @return the saved version, or why nothing was saved.
     * @throws com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException if [actor] is not allowed to.
     */
    suspend operator fun invoke(form: TaxProfileForm, actor: UserId): Result {
        access.require(actor, Permission.CONFIGURE_BILLING)
        return allowed(form, actor)
    }

    private suspend fun allowed(form: TaxProfileForm, actor: UserId): Result =
        when (val check = form.check()) {
            is TaxProfileCheck.Invalid -> Result.Invalid(check.problems, check.missingChoices)
            is TaxProfileCheck.Valid -> transactions.run { save(check.profile, actor) }
        }

    private suspend fun save(profile: TaxProfile, actor: UserId): Result {
        val current = profiles.current()
        if (current?.profile == profile) return Result.Unchanged(current)
        val version = TaxProfileVersion(
            number = (current?.number ?: 0) + 1,
            profile = profile,
            // Storage keeps milliseconds: keep what will be read back.
            savedAt = time.now().truncatedTo(ChronoUnit.MILLIS),
            savedBy = actor,
        )
        profiles.append(version)
        auditLog.record(AuditEvent.TaxProfileSaved(actor, version.number))
        return Result.Saved(version)
    }
}
