package com.claytonmuhoza.easybilling.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.SaveTaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.UserId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The tax profile versions on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class RoomTaxProfileRepositoryTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())
    private val profiles = storage.taxProfiles

    private val profile = TaxProfile(
        taxpayerType = TaxpayerType.NATURAL_PERSON,
        name = "Jean Ndayishimiye",
        tin = "4000000002",
        tradeNumber = "RC 678",
        postalNumber = "BP 1234",
        phoneNumber = "+257 79 11 22 33",
        address = Address(
            province = "Gitega",
            commune = "Gitega",
            quartier = "Magarama",
            avenue = "Avenue du Marché",
            rue = "Rue 3",
            number = "14",
        ),
        taxes = TaxRegistrations(vat = false, consumptionTax = true, flatRateWithholding = true),
        fiscalCenter = FiscalCenter.DPMC,
        activitySector = "Alimentation",
        legalForm = "Entreprise individuelle",
    )

    private fun version(number: Int, profile: TaxProfile = this.profile) = TaxProfileVersion(
        number = number,
        profile = profile,
        savedAt = Instant.parse("2026-10-03T08:00:00.250Z").plusSeconds(number.toLong()),
        savedBy = UserId(7),
    )

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `a new database has no profile`() = runTest {
        assertNull(profiles.current())
        assertEquals(emptyList(), profiles.history())
    }

    @Test
    fun `a version is read back with every field`() = runTest {
        profiles.append(version(1))

        assertEquals(version(1), profiles.current())
        assertEquals(version(1), profiles.find(1))
    }

    @Test
    fun `every taxpayer type, fiscal center and tax registration survives the round trip`() = runTest {
        val variants = TaxpayerType.entries.flatMap { type ->
            FiscalCenter.entries.map { center ->
                profile.copy(
                    taxpayerType = type,
                    fiscalCenter = center,
                    taxes = TaxRegistrations(vat = true, consumptionTax = false, flatRateWithholding = true),
                )
            }
        }
        variants.forEachIndexed { index, variant -> profiles.append(version(index + 1, variant)) }

        assertEquals(variants, profiles.history().reversed().map { it.profile })
    }

    @Test
    fun `the history is newest first and the current version is the latest`() = runTest {
        profiles.append(version(1))
        profiles.append(version(2, profile.copy(phoneNumber = "+257 22 00 00 00")))

        assertEquals(listOf(2, 1), profiles.history().map { it.number })
        assertEquals(2, profiles.current()?.number)
        assertEquals("+257 79 11 22 33", profiles.find(1)?.profile?.phoneNumber)
    }

    @Test
    fun `an unknown version is not found`() = runTest {
        assertNull(profiles.find(3))
    }

    @Test
    fun `a version number cannot be used twice`() = runTest {
        profiles.append(version(1))

        assertFailsWith<Exception> { profiles.append(version(1, profile.copy(name = "Autre"))) }
        assertEquals("Jean Ndayishimiye", profiles.find(1)?.profile?.name)
    }

    @Test
    fun `the database refuses to modify a version`() = runTest {
        profiles.append(version(1))

        assertFailsWith<Exception> {
            storage.database.openHelper.writableDatabase
                .execSQL("UPDATE tax_profile_versions SET name = 'Autre' WHERE number = 1")
        }
        assertEquals("Jean Ndayishimiye", profiles.find(1)?.profile?.name)
    }

    @Test
    fun `the database refuses to delete a version`() = runTest {
        profiles.append(version(1))

        assertFailsWith<Exception> {
            storage.database.openHelper.writableDatabase.execSQL("DELETE FROM tax_profile_versions")
        }
        assertEquals(version(1), profiles.current())
    }

    @Test
    fun `a saved version and its audit entry are stored together`() = runTest {
        val log = ChainedAuditLog(storage.auditStore, { Instant.parse("2026-10-03T08:00:00Z") }, storage.transactions)
        val save = SaveTaxProfile(profiles, log, { Instant.parse("2026-10-03T08:00:00Z") }, storage.transactions)

        assertIs<SaveTaxProfile.Result.Saved>(save(TaxProfileForm.from(profile), UserId(7)))

        assertEquals(1, profiles.current()?.number)
        assertEquals(listOf("TAX_PROFILE_SAVED"), storage.auditStore.readAll().map { it.action })
    }

    @Test
    fun `a failure after the version is written stores nothing`() = runTest {
        assertFailsWith<IllegalStateException> {
            storage.transactions.run {
                profiles.append(version(1))
                error("Simulated crash before the audit entry")
            }
        }

        assertNull(profiles.current())
    }

    @Test
    fun `the longest values of the specification are stored whole`() = runTest {
        val longest = TaxProfileField.entries.fold(TaxProfileForm.from(profile)) { form, field ->
            form.with(field, "é".repeat(field.maxLength))
        }
        val checked = assertIs<TaxProfileCheck.Valid>(
            longest.check(),
        ).profile

        profiles.append(version(1, checked))

        assertEquals(checked, profiles.current()?.profile)
    }
}
