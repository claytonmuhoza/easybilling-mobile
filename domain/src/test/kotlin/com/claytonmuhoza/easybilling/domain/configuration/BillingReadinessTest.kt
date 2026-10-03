package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.IdentifierContext
import com.claytonmuhoza.easybilling.domain.invoicing.ReadIdentifierContext
import com.claytonmuhoza.easybilling.domain.testing.InMemoryCredentialsVault
import com.claytonmuhoza.easybilling.domain.testing.InMemoryEbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryNumberingRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxProfileRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxRepository
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.TaxProfileSamples
import com.claytonmuhoza.easybilling.domain.testing.percent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What must be configured before the cash register opens (end of UC-A01). */
class BillingReadinessTest {

    private val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00Z"))
    private val today = LocalDate.of(2026, 10, 3)
    private val profiles = InMemoryTaxProfileRepository()
    private val ebms = InMemoryEbmsSettingsRepository()
    private val vault = InMemoryCredentialsVault()
    private val taxes = InMemoryTaxRepository()
    private val numbering = InMemoryNumberingRepository()
    private val readiness = CheckBillingReadiness(profiles, ebms, taxes, numbering, time)

    private val vat = percent("18", PercentageBase.PRICE_WITHOUT_VAT)

    private suspend fun saveProfile(vatTaxpayer: Boolean) {
        val form = TaxProfileSamples.form().copy(taxes = TaxRegistrations(vat = vatTaxpayer))
        val profile = assertIs<TaxProfileCheck.Valid>(form.check()).profile
        profiles.append(TaxProfileVersion((profiles.current()?.number ?: 0) + 1, profile, time.now(), UserId(1)))
    }

    private fun loginSucceeded(environment: EbmsEnvironment = EbmsEnvironment.TEST) {
        ebms.settings = ebms.settings.copy(lastSuccessfulLogins = mapOf(environment to time.now()))
    }

    private suspend fun saveNumbering() {
        numbering.save(assertIs<NumberingCheck.Valid>(NumberingForm().check(null)).settings)
    }

    @Test
    fun `nothing is ready on a new installation`() = runTest {
        val result = readiness()

        assertFalse(result.ready)
        assertEquals(
            mapOf(
                ReadinessStep.TAX_PROFILE to false,
                ReadinessStep.EBMS_CONNECTION to false,
                ReadinessStep.NUMBERING to false,
            ),
            result.steps,
        )
    }

    @Test
    fun `the cash register opens once the profile, the connection and the numbering are done`() = runTest {
        saveProfile(vatTaxpayer = false)
        assertFalse(readiness().ready)
        loginSucceeded()
        assertFalse(readiness().ready)
        saveNumbering()

        assertTrue(readiness().ready)
        assertEquals(
            setOf(ReadinessStep.TAX_PROFILE, ReadinessStep.EBMS_CONNECTION, ReadinessStep.NUMBERING),
            readiness().steps.keys,
        )
    }

    @Test
    fun `a login that succeeded in another environment does not count`() = runTest {
        saveProfile(vatTaxpayer = false)
        saveNumbering()
        loginSucceeded(EbmsEnvironment.PRODUCTION)

        assertEquals(false, readiness().steps[ReadinessStep.EBMS_CONNECTION])
    }

    @Test
    fun `a VAT taxpayer needs a VAT in force today`() = runTest {
        saveProfile(vatTaxpayer = true)
        loginSucceeded()
        saveNumbering()
        assertEquals(false, readiness().steps[ReadinessStep.VAT])

        // A consumption tax, a VAT that starts tomorrow and an archived VAT are not enough.
        taxes.create(TaxKind.CONSUMPTION_TAX, "TC", TaxVersion(today, percent("5", PercentageBase.PRICE)))
        taxes.create(TaxKind.VAT, "TVA 10 %", TaxVersion(today.plusDays(1), vat))
        val archived = taxes.create(TaxKind.VAT, "TVA 0 %", TaxVersion(today, vat))
        taxes.setArchived(archived.id, true)
        assertFalse(readiness().ready)

        taxes.create(TaxKind.VAT, "TVA 18 %", TaxVersion(today, vat))
        assertTrue(readiness().ready)
    }

    @Test
    fun `the room for a number is unknown until the NIF and a system identifier are saved`() = runTest {
        val context = ReadIdentifierContext(profiles, ebms, vault)
        assertEquals(IdentifierContext(tin = null, activeSystemId = null, roomForNumber = null), context())
        saveProfile(vatTaxpayer = false)
        assertNull(context().roomForNumber)
        assertNull(context().identifierOf(time.now(), "FN-00001/2026"))

        // NIF of 10 characters: 90 - 10 - 14 - 3 separators = 63 characters to share.
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("ws4000000001", "secret"))
        assertEquals(51, context().roomForNumber)
        assertEquals(
            "4000000001/ws4000000001/20261003100000/FN-00001/2026",
            context().identifierOf(time.now(), "FN-00001/2026"),
        )

        // The longest system identifier decides of the room; the active one is written.
        vault.write(EbmsEnvironment.PRODUCTION, EbmsCredentials("ws400000000100027", "secret"))
        assertEquals(46, context().roomForNumber)
        assertEquals("ws4000000001", context().activeSystemId)
    }

    @Test
    fun `no identifier is built with a number that does not fit`() {
        val context = IdentifierContext(tin = "1".repeat(15), activeSystemId = "w".repeat(30), roomForNumber = 28)

        assertNull(context.identifierOf(time.now(), "N".repeat(29)))
        assertEquals(90, context.identifierOf(time.now(), "N".repeat(28))?.length)
    }
}
