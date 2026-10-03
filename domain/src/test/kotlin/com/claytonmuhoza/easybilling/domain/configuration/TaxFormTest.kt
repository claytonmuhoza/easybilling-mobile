package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.testing.percent
import com.claytonmuhoza.easybilling.domain.testing.perUnit
import com.claytonmuhoza.easybilling.domain.testing.rateAt
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Rules of a tax definition (UC-A07). */
class TaxFormTest {

    private val today = LocalDate.of(2026, 10, 3)
    private val vat = TaxForm(
        kind = TaxKind.VAT,
        name = "TVA 18 %",
        rate = TaxRateForm(mode = TaxMode.PERCENTAGE, value = "18", validFrom = today),
    )

    private fun problemsOf(form: TaxForm) = assertIs<TaxCheck.Invalid>(form.check(today)).problems

    private fun TaxForm.withRate(change: TaxRateForm.() -> TaxRateForm) = copy(rate = rate.change())

    private fun rate(form: TaxForm) = assertIs<TaxCheck.Valid>(form.check(today)).version

    @Test
    fun `the OBR fields of each tax are those of the specification`() {
        assertEquals(
            listOf("vat", "item_ct", "item_tl", "item_ott_tax", "item_tsce_tax"),
            TaxKind.entries.map { it.obrField },
        )
    }

    @Test
    fun `a VAT is a percentage of the price without VAT`() {
        val checked = assertIs<TaxCheck.Valid>(vat.check(today))

        assertEquals(TaxKind.VAT, checked.kind)
        assertEquals("TVA 18 %", checked.name)
        assertEquals(TaxVersion(today, percent("18", PercentageBase.PRICE_WITHOUT_VAT)), checked.version)
    }

    @Test
    fun `a VAT cannot be an amount per unit or a free amount`() {
        assertEquals(setOf(TaxProblem.MODE_NOT_ALLOWED), problemsOf(vat.withRate { copy(mode = TaxMode.PER_UNIT) }))
        assertEquals(setOf(TaxProblem.MODE_NOT_ALLOWED), problemsOf(vat.withRate { copy(mode = TaxMode.FREE_AMOUNT) }))
    }

    @Test
    fun `the TC, the OTT and the TSCE in percent are computed on the price times the quantity`() {
        for (kind in listOf(TaxKind.CONSUMPTION_TAX, TaxKind.OTT, TaxKind.TSCE)) {
            val form = vat.copy(kind = kind, rate = vat.rate.copy(value = "2", base = PercentageBase.PRICE_WITH_VAT))

            assertEquals(percent("2", PercentageBase.PRICE), rate(form).computation, "$kind")
        }
    }

    @Test
    fun `the PFL in percent needs its base`() {
        val pfl = vat.copy(kind = TaxKind.FLAT_RATE_WITHHOLDING, rate = vat.rate.copy(value = "1"))

        assertEquals(setOf(TaxProblem.BASE_MISSING), problemsOf(pfl))
        for (base in PercentageBase.entries) {
            assertEquals(
                percent("1", base),
                rate(pfl.copy(rate = pfl.rate.copy(base = base))).computation,
            )
        }
    }

    @Test
    fun `every tax other than the VAT may be an amount per unit or a free amount`() {
        for (kind in TaxKind.entries - TaxKind.VAT) {
            val perUnit = vat.copy(kind = kind, rate = TaxRateForm(TaxMode.PER_UNIT, "2.5", validFrom = today))
            val free = vat.copy(kind = kind, rate = TaxRateForm(TaxMode.FREE_AMOUNT, "", validFrom = today))

            assertEquals(perUnit("2.5"), rate(perUnit).computation, "$kind")
            assertEquals(TaxComputation.FreeAmount, rate(free).computation, "$kind")
        }
    }

    @Test
    fun `a comma is read as the decimal separator`() {
        assertEquals(
            percent("16.5", PercentageBase.PRICE_WITHOUT_VAT),
            rate(vat.copy(rate = vat.rate.copy(value = " 16,5 "))).computation,
        )
    }

    @Test
    fun `a value must be a number of zero or more with two decimals at most`() {
        for (value in listOf("abc", "-1", "1.234", "1,2,3")) {
            assertEquals(setOf(TaxProblem.VALUE_INVALID), problemsOf(vat.withRate { copy(value = value) }), value)
        }
        assertEquals(setOf(TaxProblem.VALUE_MISSING), problemsOf(vat.copy(rate = vat.rate.copy(value = " "))))
        assertIs<TaxCheck.Valid>(vat.copy(rate = vat.rate.copy(value = "0")).check(today))
    }

    @Test
    fun `a percentage cannot exceed 100`() {
        assertEquals(setOf(TaxProblem.RATE_ABOVE_100), problemsOf(vat.copy(rate = vat.rate.copy(value = "100.01"))))
        assertIs<TaxCheck.Valid>(vat.copy(rate = vat.rate.copy(value = "100")).check(today))
    }

    @Test
    fun `a name is required, 50 characters at most`() {
        assertEquals(setOf(TaxProblem.NAME_MISSING), problemsOf(vat.copy(name = "  ")))
        assertEquals(setOf(TaxProblem.NAME_TOO_LONG), problemsOf(vat.copy(name = "x".repeat(51))))
        assertEquals("x".repeat(50), assertIs<TaxCheck.Valid>(vat.copy(name = " " + "x".repeat(50)).check(today)).name)
    }

    @Test
    fun `the kind and the mode must be chosen`() {
        assertEquals(setOf(TaxProblem.KIND_MISSING), problemsOf(vat.copy(kind = null)))
        assertEquals(setOf(TaxProblem.MODE_MISSING), problemsOf(vat.copy(rate = vat.rate.copy(mode = null))))
    }

    @Test
    fun `a rate never starts in the past`() {
        assertEquals(setOf(TaxProblem.DATE_IN_PAST), problemsOf(vat.withRate { copy(validFrom = today.minusDays(1)) }))
        assertEquals(setOf(TaxProblem.DATE_MISSING), problemsOf(vat.copy(rate = vat.rate.copy(validFrom = null))))
        assertEquals(today.plusDays(30), rate(vat.copy(rate = vat.rate.copy(validFrom = today.plusDays(30)))).validFrom)
    }

    @Test
    fun `a new rate starts after the latest one`() {
        val latest = today.plusDays(10)

        val refused = vat.rate.copy(validFrom = latest).check(TaxKind.VAT, today, latest)
        val accepted = vat.rate.copy(validFrom = latest.plusDays(1)).check(TaxKind.VAT, today, latest)

        assertEquals(TaxRateCheck.Invalid(setOf(TaxProblem.DATE_NOT_AFTER_LATEST)), refused)
        assertIs<TaxRateCheck.Valid>(accepted)
    }

    @Test
    fun `every problem is reported at once`() {
        val form = TaxForm(kind = null, name = "", rate = TaxRateForm(mode = null, validFrom = null))

        assertEquals(
            setOf(TaxProblem.KIND_MISSING, TaxProblem.NAME_MISSING, TaxProblem.MODE_MISSING, TaxProblem.DATE_MISSING),
            problemsOf(form),
        )
    }

    @Test
    fun `a definition applies the rate in force on a date`() {
        val tax = TaxDefinition(
            id = TaxId(1),
            kind = TaxKind.VAT,
            name = "TVA",
            versions = listOf(
                TaxVersion(LocalDate.of(2026, 1, 1), percent("18", PercentageBase.PRICE_WITHOUT_VAT)),
                TaxVersion(LocalDate.of(2027, 1, 1), percent("16", PercentageBase.PRICE_WITHOUT_VAT)),
            ),
        )

        assertNull(tax.computationAt(LocalDate.of(2025, 12, 31)))
        assertEquals(BigDecimal("18"), tax.rateAt(LocalDate.of(2026, 12, 31)))
        assertEquals(BigDecimal("16"), tax.rateAt(LocalDate.of(2027, 1, 1)))
        assertEquals(LocalDate.of(2027, 1, 1), tax.latest.validFrom)
    }
}
