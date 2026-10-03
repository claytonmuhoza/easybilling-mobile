package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.testing.TaxProfileSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Rules of the tax profile (UC-A02): required fields and sizes of the OBR specification. */
class TaxProfileFormTest {

    private val valid = TaxProfileSamples.form()

    private fun problemsOf(form: TaxProfileForm): Map<TaxProfileField, FieldProblem> =
        assertIs<TaxProfileCheck.Invalid>(form.check()).problems

    @Test
    fun `the limits are those of the OBR specification`() {
        val expected = mapOf(
            TaxProfileField.NAME to 100,
            TaxProfileField.TIN to 15,
            TaxProfileField.TRADE_NUMBER to 20,
            TaxProfileField.POSTAL_NUMBER to 20,
            TaxProfileField.PHONE_NUMBER to 20,
            TaxProfileField.PROVINCE to 50,
            TaxProfileField.COMMUNE to 50,
            TaxProfileField.QUARTIER to 50,
            TaxProfileField.AVENUE to 50,
            TaxProfileField.RUE to 50,
            TaxProfileField.ADDRESS_NUMBER to 10,
            TaxProfileField.ACTIVITY_SECTOR to 20,
            TaxProfileField.LEGAL_FORM to 50,
        )

        assertEquals(expected, TaxProfileField.entries.associateWith { it.maxLength })
    }

    @Test
    fun `only the postal number, the street and the number may stay empty`() {
        val optional = TaxProfileField.entries.filterNot { it.required }.toSet()

        assertEquals(
            setOf(TaxProfileField.POSTAL_NUMBER, TaxProfileField.RUE, TaxProfileField.ADDRESS_NUMBER),
            optional,
        )
    }

    @Test
    fun `a complete form gives the profile`() {
        val profile = assertIs<TaxProfileCheck.Valid>(valid.check()).profile

        assertEquals(TaxpayerType.LEGAL_PERSON, profile.taxpayerType)
        assertEquals("Boutique Umuco SPRL", profile.name)
        assertEquals("4000000001", profile.tin)
        assertEquals("RC 12345", profile.tradeNumber)
        assertEquals("", profile.postalNumber)
        assertEquals("+257 22 22 22 22", profile.phoneNumber)
        assertEquals(
            Address(
                province = "Bujumbura Mairie",
                commune = "Mukaza",
                quartier = "Rohero",
                avenue = "Avenue de l'Industrie",
                rue = "",
                number = "",
            ),
            profile.address,
        )
        assertEquals(TaxRegistrations(vat = true, consumptionTax = false, flatRateWithholding = false), profile.taxes)
        assertEquals(FiscalCenter.DMC, profile.fiscalCenter)
        assertEquals("Commerce general", profile.activitySector)
        assertEquals("SPRL", profile.legalForm)
    }

    @Test
    fun `every text is trimmed`() {
        val form = valid.with(TaxProfileField.NAME, "  Boutique Umuco SPRL  ").with(TaxProfileField.RUE, "  12  ")

        val profile = assertIs<TaxProfileCheck.Valid>(form.check()).profile

        assertEquals("Boutique Umuco SPRL", profile.name)
        assertEquals("12", profile.address.rue)
    }

    @Test
    fun `each text field accepts its exact limit`() {
        for (field in TaxProfileField.entries) {
            val form = valid.with(field, "x".repeat(field.maxLength))

            assertIs<TaxProfileCheck.Valid>(form.check(), "$field at its limit of ${field.maxLength}")
        }
    }

    @Test
    fun `each text field refuses one character more than its limit`() {
        for (field in TaxProfileField.entries) {
            val form = valid.with(field, "x".repeat(field.maxLength + 1))

            assertEquals(mapOf(field to FieldProblem.TooLong(field.maxLength)), problemsOf(form), "$field")
        }
    }

    @Test
    fun `the limit is checked after trimming`() {
        val form = valid.with(TaxProfileField.TIN, "  " + "1".repeat(15) + "  ")

        assertIs<TaxProfileCheck.Valid>(form.check())
    }

    @Test
    fun `each required field refuses an empty or blank value`() {
        for (field in TaxProfileField.entries.filter { it.required }) {
            assertEquals(mapOf(field to FieldProblem.Missing), problemsOf(valid.with(field, "")), "$field empty")
            assertEquals(mapOf(field to FieldProblem.Missing), problemsOf(valid.with(field, "   ")), "$field blank")
        }
    }

    @Test
    fun `a missing text is missing`() {
        val form = valid.copy(texts = valid.texts - TaxProfileField.COMMUNE)

        assertEquals(mapOf(TaxProfileField.COMMUNE to FieldProblem.Missing), problemsOf(form))
    }

    @Test
    fun `the taxpayer type and the fiscal center must be chosen`() {
        val form = valid.copy(taxpayerType = null, fiscalCenter = null)

        val invalid = assertIs<TaxProfileCheck.Invalid>(form.check())

        assertEquals(setOf(TaxProfileChoice.TAXPAYER_TYPE, TaxProfileChoice.FISCAL_CENTER), invalid.missingChoices)
        assertEquals(emptyMap(), invalid.problems)
    }

    @Test
    fun `every problem is reported at once`() {
        val form = valid
            .with(TaxProfileField.NAME, "")
            .with(TaxProfileField.TIN, "1".repeat(16))
            .copy(fiscalCenter = null)

        val invalid = assertIs<TaxProfileCheck.Invalid>(form.check())

        assertEquals(
            mapOf(TaxProfileField.NAME to FieldProblem.Missing, TaxProfileField.TIN to FieldProblem.TooLong(15)),
            invalid.problems,
        )
        assertEquals(setOf(TaxProfileChoice.FISCAL_CENTER), invalid.missingChoices)
    }

    @Test
    fun `a profile turned back into a form gives the same profile`() {
        val profile = assertIs<TaxProfileCheck.Valid>(valid.check()).profile

        assertEquals(TaxProfileCheck.Valid(profile), TaxProfileForm.from(profile).check())
    }

    @Test
    fun `the codes are those of the OBR specification`() {
        assertEquals(listOf("1", "2"), TaxpayerType.entries.map { it.code })
        assertEquals(listOf("DGC", "DMC", "DPMC"), FiscalCenter.entries.map { it.code })
    }
}
