package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxProfileRepository
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import com.claytonmuhoza.easybilling.domain.testing.TaxProfileSamples
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Saving the tax profile keeps every previous version (UC-A02). */
class SaveTaxProfileTest {

    private val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00.123456Z"))
    private val profiles = InMemoryTaxProfileRepository()
    private val audit = RecordingAuditLog()
    private val save = SaveTaxProfile(profiles, audit, time, DirectTransactionRunner)

    private val alice = UserId(1)
    private val form = TaxProfileSamples.form()

    @Test
    fun `a device has no profile at first`() = runTest {
        assertNull(profiles.current())
        assertEquals(emptyList(), profiles.history())
    }

    @Test
    fun `the first save creates version 1`() = runTest {
        val saved = assertIs<SaveTaxProfile.Result.Saved>(save(form, alice)).version

        assertEquals(1, saved.number)
        assertEquals(alice, saved.savedBy)
        assertEquals(Instant.parse("2026-10-03T08:00:00.123Z"), saved.savedAt)
        assertEquals(assertIs<TaxProfileCheck.Valid>(form.check()).profile, saved.profile)
        assertEquals(saved, profiles.current())
    }

    @Test
    fun `a change creates a new version and keeps the previous one intact`() = runTest {
        val first = assertIs<SaveTaxProfile.Result.Saved>(save(form, alice)).version
        time.advance(Duration.ofDays(30))

        val second = assertIs<SaveTaxProfile.Result.Saved>(
            save(form.with(TaxProfileField.PHONE_NUMBER, "+257 79 00 00 00"), alice),
        ).version

        assertEquals(2, second.number)
        assertEquals("+257 79 00 00 00", second.profile.phoneNumber)
        assertEquals(second, profiles.current())
        assertEquals(listOf(second, first), profiles.history())
        assertEquals(first, profiles.find(1))
        assertEquals("+257 22 22 22 22", profiles.find(1)?.profile?.phoneNumber)
    }

    @Test
    fun `saving the same profile again creates no version`() = runTest {
        val first = assertIs<SaveTaxProfile.Result.Saved>(save(form, alice)).version

        val result = save(form.with(TaxProfileField.NAME, "  Boutique Umuco SPRL "), alice)

        assertEquals(SaveTaxProfile.Result.Unchanged(first), result)
        assertEquals(listOf(first), profiles.history())
        assertEquals(1, audit.events.size)
    }

    @Test
    fun `an invalid form is refused with its problems and nothing is stored`() = runTest {
        val result = save(form.with(TaxProfileField.TIN, "1".repeat(16)).copy(fiscalCenter = null), alice)

        val invalid = assertIs<SaveTaxProfile.Result.Invalid>(result)
        assertEquals(mapOf(TaxProfileField.TIN to FieldProblem.TooLong(15)), invalid.problems)
        assertEquals(setOf(TaxProfileChoice.FISCAL_CENTER), invalid.missingChoices)
        assertNull(profiles.current())
        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `each saved version is written to the audit log`() = runTest {
        save(form, alice)
        save(form.with(TaxProfileField.LEGAL_FORM, "SA"), alice)

        assertEquals(
            listOf<AuditEvent>(AuditEvent.TaxProfileSaved(alice, 1), AuditEvent.TaxProfileSaved(alice, 2)),
            audit.events,
        )
        assertEquals("version=2", audit.events.last().details)
        assertEquals("TAX_PROFILE_SAVED", audit.events.last().action)
    }
}
