package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.FakeAccessControl
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxRepository
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import com.claytonmuhoza.easybilling.domain.testing.percent
import com.claytonmuhoza.easybilling.domain.testing.rateAt
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Creating taxes, scheduling a new rate and archiving (UC-A07). */
class TaxUseCasesTest {

    // 23:30 in UTC is already the next day in Bujumbura (UTC+2).
    private val time = MutableTimeProvider(Instant.parse("2026-10-02T23:30:00Z"))
    private val taxes = InMemoryTaxRepository()
    private val audit = RecordingAuditLog()
    private val access = FakeAccessControl()
    private val create = CreateTax(access, taxes, audit, time, DirectTransactionRunner)
    private val schedule = ScheduleTaxRate(access, taxes, audit, time, DirectTransactionRunner)
    private val archive = ArchiveTax(access, taxes, audit, DirectTransactionRunner)

    private val alice = UserId(1)
    private val today = LocalDate.of(2026, 10, 3)
    private val vatForm = TaxForm(
        kind = TaxKind.VAT,
        name = "TVA 18 %",
        rate = TaxRateForm(mode = TaxMode.PERCENTAGE, value = "18", validFrom = today),
    )

    @Test
    fun `the day of the business is the day in Bujumbura`() {
        assertEquals(today, BusinessCalendar.today(time))
    }

    @Test
    fun `a tax is created with its first rate and audited`() = runTest {
        val tax = assertIs<CreateTax.Result.Created>(create(vatForm, alice)).tax

        assertEquals(listOf(tax), taxes.all())
        assertEquals(
            listOf(TaxVersion(today, percent("18", PercentageBase.PRICE_WITHOUT_VAT))),
            tax.versions,
        )
        assertEquals(AuditEvent.TaxCreated(alice, tax.id, TaxKind.VAT), audit.events.single())
        assertEquals("tax=${tax.id.value};kind=VAT", audit.events.single().details)
    }

    @Test
    fun `an invalid tax is refused and nothing is stored`() = runTest {
        val result = create(vatForm.copy(name = ""), alice)

        assertEquals(CreateTax.Result.Invalid(setOf(TaxProblem.NAME_MISSING)), result)
        assertEquals(emptyList(), taxes.all())
        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `a new rate is scheduled from a later date and the previous one still applies before`() = runTest {
        val tax = assertIs<CreateTax.Result.Created>(create(vatForm, alice)).tax
        val newRate = TaxRateForm(mode = TaxMode.PERCENTAGE, value = "16", validFrom = LocalDate.of(2027, 1, 1))

        assertEquals(ScheduleTaxRate.Result.Scheduled, schedule(tax.id, newRate, alice))

        val updated = taxes.find(tax.id)!!
        assertEquals(BigDecimal("18"), updated.rateAt(LocalDate.of(2026, 12, 31)))
        assertEquals(BigDecimal("16"), updated.rateAt(LocalDate.of(2027, 1, 1)))
        assertEquals(AuditEvent.TaxRateScheduled(alice, tax.id, LocalDate.of(2027, 1, 1)), audit.events.last())
        assertEquals("tax=${tax.id.value};from=2027-01-01", audit.events.last().details)
    }

    @Test
    fun `a new rate cannot start on or before the latest one`() = runTest {
        val tax = assertIs<CreateTax.Result.Created>(create(vatForm, alice)).tax

        val result = schedule(tax.id, TaxRateForm(TaxMode.PERCENTAGE, "16", validFrom = today), alice)

        assertEquals(ScheduleTaxRate.Result.Invalid(setOf(TaxProblem.DATE_NOT_AFTER_LATEST)), result)
        assertEquals(1, taxes.find(tax.id)!!.versions.size)
    }

    @Test
    fun `the kind of a tax limits its new rates`() = runTest {
        val tax = assertIs<CreateTax.Result.Created>(create(vatForm, alice)).tax

        val result = schedule(tax.id, TaxRateForm(TaxMode.PER_UNIT, "5", validFrom = today.plusDays(1)), alice)

        assertEquals(ScheduleTaxRate.Result.Invalid(setOf(TaxProblem.MODE_NOT_ALLOWED)), result)
    }

    @Test
    fun `an unknown tax cannot get a new rate nor be archived`() = runTest {
        assertEquals(ScheduleTaxRate.Result.NotFound, schedule(TaxId(9), TaxRateForm(), alice))
        assertEquals(ArchiveTax.Result.NotFound, archive(TaxId(9), archived = true, alice))
    }

    @Test
    fun `an archived tax is kept, marked archived, and can come back`() = runTest {
        val tax = assertIs<CreateTax.Result.Created>(create(vatForm, alice)).tax

        assertEquals(ArchiveTax.Result.Done, archive(tax.id, archived = true, alice))
        assertEquals(true, taxes.find(tax.id)!!.archived)
        assertEquals(AuditEvent.TaxArchived(alice, tax.id, archived = true), audit.events.last())
        assertEquals("tax=${tax.id.value};archived=true", audit.events.last().details)

        archive(tax.id, archived = false, alice)
        assertEquals(false, taxes.find(tax.id)!!.archived)
    }

    @Test
    fun `a user who may not configure the billing changes no tax`() = runTest {
        access.denied += Permission.CONFIGURE_BILLING
        val rate = TaxRateForm(TaxMode.PERCENTAGE, "16", validFrom = today)

        assertFailsWith<PermissionDeniedException> { create(vatForm, alice) }
        assertFailsWith<PermissionDeniedException> { schedule(TaxId(1), rate, alice) }
        assertFailsWith<PermissionDeniedException> { archive(TaxId(1), archived = true, alice) }

        assertEquals(List(3) { alice to Permission.CONFIGURE_BILLING }, access.required)
        assertEquals(emptyList(), audit.events)
    }
}
