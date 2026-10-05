package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.pressInSettings
import com.claytonmuhoza.easybilling.testing.replaceInForm
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
import java.time.Duration
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Tax profile (UC-A02), entered by the administrator "Alice", already logged in. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class TaxProfileFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var profiles: TaxProfileRepository

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun logInAliceThenLaunch() {
        hiltRule.inject()
        runBlocking {
            val alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456"))
            sessions.open(alice.user)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() = scenario.close()

    private fun openForm() {
        composeRule.pressInSettings("Tax profile")
        composeRule.waitForText("Save")
    }

    /** Fills every required field of an empty form. */
    private fun fillRequired() {
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("Name or trade name", "Boutique Umuco SPRL")
        composeRule.typeInForm("Tax identification number (NIF)", "4000000001")
        composeRule.typeInForm("Trade register number", "RC 12345")
        composeRule.typeInForm("Phone number", "+257 22 22 22 22")
        composeRule.typeInForm("Province", "Bujumbura Mairie")
        composeRule.typeInForm("Commune", "Mukaza")
        composeRule.typeInForm("Quartier or colline", "Rohero")
        composeRule.typeInForm("Avenue", "Avenue de l'Industrie")
        composeRule.pressInForm("DMC")
        composeRule.pressInForm("Registered for VAT")
        composeRule.typeInForm("Activity sector", "Commerce general")
        composeRule.typeInForm("Legal form", "SPRL")
    }

    private fun saveFirstVersion() {
        openForm()
        fillRequired()
        composeRule.pressInForm("Save")
        composeRule.waitForText("Version 1")
    }

    @Test
    fun `the settings ask for the tax profile while there is none`() {
        composeRule.openSettings()
        composeRule.waitForText("Tax profile").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("To complete").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a complete profile is saved as version 1 and the settings show it`() {
        saveFirstVersion()

        // Saving comes back to the settings, where the profile was opened from.
        composeRule.onNodeWithText("Before the first invoice").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Version 1").performScrollTo().assertIsDisplayed()
        val saved = runBlocking { profiles.current() }
        assertEquals(1, saved?.number)
        assertEquals("Boutique Umuco SPRL", saved?.profile?.name)
        assertEquals(true, saved?.profile?.taxes?.vat)
        assertEquals(false, saved?.profile?.taxes?.consumptionTax)
        assertEquals("", saved?.profile?.address?.rue)
        assertEquals("TAX_PROFILE_SAVED", runBlocking { auditStore.readAll().last().action })
    }

    @Test
    fun `an empty form shows what is required and saves nothing`() {
        openForm()

        composeRule.pressInForm("Save")

        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTextCount("Required") > 0 }
        // One message per required text field, and one per choice not made.
        assertEquals(10, composeRule.onAllNodesWithTextCount("Required"))
        assertEquals(2, composeRule.onAllNodesWithTextCount("Choose an option"))
        assertNull(runBlocking { profiles.current() })
    }

    @Test
    fun `a value longer than the OBR limit is refused`() {
        openForm()
        fillRequired()
        composeRule.replaceInForm("Tax identification number (NIF)", "1234567890123456")

        composeRule.pressInForm("Save")

        composeRule.waitForText("At most 15 characters").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("16 / 15").assertIsDisplayed()
        assertNull(runBlocking { profiles.current() })
    }

    @Test
    fun `the optional fields may stay empty`() {
        openForm()

        composeRule.onNodeWithText("Post office box").performScrollTo().assertIsDisplayed()
        assertEquals(3, composeRule.onAllNodesWithTextCount("Optional"))
    }

    @Test
    fun `a change creates version 2 and version 1 stays readable in the history`() {
        saveFirstVersion()
        clock.advance(Duration.ofDays(1))

        openForm()
        composeRule.replaceInForm("Phone number", "+257 79 00 00 00")
        composeRule.pressInForm("Save")
        composeRule.waitForText("Version 2")

        openForm()
        composeRule.onNodeWithText("History").performClick()
        composeRule.waitForText("Version 1").performClick()

        composeRule.waitForText("+257 22 22 22 22").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("+257 79 00 00 00").assertDoesNotExist()
        assertEquals(listOf(2, 1), runBlocking { profiles.history().map { it.number } })
    }

    @Test
    fun `the history lists every version, newest first, with its author`() {
        saveFirstVersion()
        openForm()
        composeRule.replaceInForm("Legal form", "SA")
        composeRule.pressInForm("Save")
        composeRule.waitForText("Version 2")

        openForm()
        composeRule.onNodeWithText("History").performClick()

        composeRule.waitForText("Version 2").assertIsDisplayed()
        composeRule.onNodeWithText("Version 1").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithTextCount("by Alice", substring = true))
    }

    @Test
    fun `saving the same profile again creates no new version`() {
        saveFirstVersion()

        openForm()
        composeRule.pressInForm("Save")

        composeRule.waitForText("Version 1").assertIsDisplayed()
        assertEquals(listOf(1), runBlocking { profiles.history().map { it.number } })
    }

    @Test
    fun `the history is empty before the first save`() {
        openForm()
        composeRule.onNodeWithText("History").performClick()

        composeRule.waitForText("No profile saved yet").assertIsDisplayed()
    }

    @Test
    fun `the back arrow leaves the form without saving`() {
        openForm()
        composeRule.typeInForm("Name or trade name", "Boutique Umuco SPRL")

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("To complete").performScrollTo().assertIsDisplayed()
        assertNull(runBlocking { profiles.current() })
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the form is translated in French`() {
        composeRule.pressInSettings("Profil fiscal", settings = "Paramètres")

        composeRule.waitForText("Nom ou raison sociale").assertIsDisplayed()
        composeRule.onNodeWithText("Personne morale").assertIsDisplayed()
        composeRule.onNodeWithText("Quartier ou colline").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Assujetti à la TVA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enregistrer").performScrollTo().assertIsDisplayed()
    }
}
