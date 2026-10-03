package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.typeInForm
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Numbering (UC-A06) and end of the setup (UC-A01), by the administrator "Alice", already logged in. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class NumberingFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var numbering: NumberingRepository

    @Inject
    lateinit var saveNumbering: SaveNumbering

    @Inject
    lateinit var allocate: AllocateInvoiceNumber

    @Inject
    lateinit var profiles: TaxProfileRepository

    @Inject
    lateinit var ebmsSettings: EbmsSettingsRepository

    @Inject
    lateinit var vault: EbmsCredentialsVault

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun logInAlice() {
        hiltRule.inject()
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
        }
    }

    @After
    fun closeActivity() = scenario.close()

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** @param status what the home screen says of the numbering, the last status it loads. */
    private fun openNumbering(status: String = "Not set") {
        // The cards move when the sentence above them is loaded: wait for the home screen to settle.
        composeRule.waitForText(status)
        composeRule.pressInForm("Numbering")
        composeRule.waitForText("Next numbers")
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    private fun saveProfile(vatTaxpayer: Boolean = false) = runBlocking {
        val profile = TaxProfile(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            name = "Boutique Umuco SPRL",
            tin = "4000000001",
            tradeNumber = "RC 12345",
            postalNumber = "",
            phoneNumber = "+257 22 22 22 22",
            address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
            taxes = TaxRegistrations(vat = vatTaxpayer),
            fiscalCenter = FiscalCenter.DMC,
            activitySector = "Commerce general",
            legalForm = "SPRL",
        )
        profiles.append(TaxProfileVersion(1, profile, clock.now(), alice.id))
    }

    private fun saveCredentials(loginSucceeded: Boolean) = runBlocking {
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("ws400000000100027", "secret"))
        if (loginSucceeded) {
            val settings = ebmsSettings.read()
            ebmsSettings.write(settings.copy(lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to clock.now())))
        }
    }

    private fun stored(): NumberingSettings? = runBlocking { numbering.settings() }

    @Test
    fun `a new installation is not ready to invoice and has no numbering`() {
        launch()

        shown(NOT_READY)
        assertEquals(3, composeRule.onAllNodesWithTextCount("To do"))
        shown("Not set")
    }

    @Test
    fun `the default numbering is proposed with its next numbers and saved`() {
        launch()
        openNumbering()

        shown("FN-00001/2026")
        shown("FA-00001/2026")
        shown("RC-00001/2026")
        shown("RHF-00001/2026")
        shown("Shown once the tax profile and the EBMS connection are saved.")
        assertNull(stored())

        composeRule.pressInForm("Save")

        shown("Numbering saved.")
        assertEquals(NumberingSettings(NumberingForm.DEFAULT_TEMPLATE, CounterReset.YEARLY, false, ""), stored())
        assertEquals("NUMBERING_SAVED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `several devices need a device code, written first in every number`() {
        launch()
        openNumbering()

        composeRule.pressInForm("Other devices also invoice for this NIF")
        shown("Enter 1 to 4 letters or digits.")
        shown("The next numbers appear once the fields are valid.")
        composeRule.pressInForm("Save")
        assertNull(stored())

        composeRule.typeInForm("Device code", "a1")
        shown("A1-FN-00001/2026")
        composeRule.pressInForm("Save")
        shown("Numbering saved.")

        assertEquals(NumberingSettings("{DEVICE}-{TYPE}-{NNNNN}/{YYYY}", CounterReset.YEARLY, true, "A1"), stored())
    }

    @Test
    fun `the fields compose the number and a yearly restart needs the year`() {
        launch()
        openNumbering()

        composeRule.typeInForm("Prefix", "BJ.")
        composeRule.pressInForm("6")
        shown("BJ.FN-000001/2026")

        composeRule.pressInForm("No year")
        shown("Starting again every year needs the year in the number.")
        composeRule.pressInForm("Never")
        shown("BJ.RHF-000001")
        composeRule.pressInForm("Save")
        shown("Numbering saved.")

        assertEquals(NumberingSettings("BJ.{TYPE}-{NNNNNN}", CounterReset.NEVER, false, ""), stored())
    }

    @Test
    fun `a saved numbering is shown again on the home screen and in its fields`() {
        val form = NumberingForm("{DEVICE}/{TYPE}/{NNNN}-{YY}", multipleDevices = true, deviceCode = "Z9")
        runBlocking { saveNumbering(form, null, alice.id) }
        launch()

        shown("Z9/FN/0001-26")
        openNumbering("Z9/FN/0001-26")

        shown("Z9/RC/0001-26")
        composeRule.onNodeWithText("Device code").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the identifier of the next invoice is shown once the profile and the system identifier are known`() {
        saveProfile()
        saveCredentials(loginSucceeded = false)
        launch()
        openNumbering()

        // The test clock shows 08:00 in UTC: 10:00 in Bujumbura.
        shown("4000000001/ws400000000100027/20261002100000/FN-00001/2026")
    }

    @Test
    fun `once a number is given the numbering is locked and shows the next numbers`() {
        runBlocking {
            saveNumbering(NumberingForm(), null, alice.id)
            assertIs<AllocateInvoiceNumber.Result.Allocated>(allocate(DocumentType.FN, clock.now()))
        }
        launch()

        shown("FN-00001/2026 · locked")
        openNumbering("FN-00001/2026 · locked")

        composeRule.waitForText("Locked since", substring = true).assertIsDisplayed()
        shown("FN-00002/2026")
        shown("FA-00001/2026")
        assertEquals(0, composeRule.onAllNodesWithTextCount("Save"))
    }

    @Test
    fun `the cash register is ready once the profile, the connection and the numbering are done`() {
        saveProfile()
        saveCredentials(loginSucceeded = true)
        runBlocking { saveNumbering(NumberingForm(), null, alice.id) }
        launch()

        shown("Ready to invoice.")
        assertEquals(3, composeRule.onAllNodesWithTextCount("Done"))
        assertEquals(0, composeRule.onAllNodesWithTextCount("To do"))
    }

    @Test
    fun `a VAT taxpayer without a VAT is not ready`() {
        saveProfile(vatTaxpayer = true)
        saveCredentials(loginSucceeded = true)
        runBlocking { saveNumbering(NumberingForm(), null, alice.id) }
        launch()

        shown(NOT_READY)
        assertEquals(1, composeRule.onAllNodesWithTextCount("To do"))
    }

    private companion object {
        const val NOT_READY = "Cash register locked: finish the steps marked “To do”."
    }
}
