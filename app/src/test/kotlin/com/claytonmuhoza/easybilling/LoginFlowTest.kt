package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.enterPin
import com.claytonmuhoza.easybilling.testing.press
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressKey
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.type
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

/** Login (UC-H02) on a device whose administrator "Alice" already exists, with PIN 123456. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class LoginFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var auditLog: AuditLog

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var profiles: TaxProfileRepository

    @Inject
    lateinit var ebmsSettings: EbmsSettingsRepository

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var alice: User

    @Before
    fun createAliceThenLaunch() {
        hiltRule.inject()
        alice = assertIs<CreateAdministrator.Result.Created>(
            runBlocking { createAdministrator("Alice", "123456", "123456") },
        ).user
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() = scenario.close()

    /** A new activity has new view models: what it shows can only come from the storage. */
    private fun relaunch() {
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun logIn(pin: String) {
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(pin)
        composeRule.pressKey("Log in")
    }

    private fun auditedActions(): List<String> = runBlocking { auditStore.readAll().map { it.action } }

    /** The audit entry of a logout is written just after the screen changes: wait for it. */
    private fun waitForAudit(action: String) {
        composeRule.waitUntil(10_000) { auditedActions().last() == action }
    }

    /** Types a wrong PIN [times] times, waiting for the answer to each attempt. */
    private fun failLogin(times: Int) {
        repeat(times) { attempt ->
            logIn("000000")
            composeRule.waitForText(
                when (val left = 9 - attempt) {
                    0 -> LOCKED_FOR_60_SECONDS
                    1 -> "Wrong PIN. 1 attempt left."
                    else -> "Wrong PIN. $left attempts left."
                },
            )
        }
    }

    /** Opens the new PIN screen and goes through its three steps. */
    private fun resetPin(recoveryCode: String, pin: String = "987654", confirmation: String = pin) {
        composeRule.waitForText("Forgot your PIN?").performScrollTo().performClick()
        composeRule.waitForText("Choose a new PIN")
        composeRule.type("Recovery code", recoveryCode)
        composeRule.pressInForm("Continue")
        composeRule.waitForText("New PIN")
        composeRule.enterPin(pin)
        composeRule.pressKey("Continue")
        composeRule.waitForText("Confirm the PIN")
        composeRule.enterPin(confirmation)
        composeRule.pressKey("Change the PIN")
    }

    private fun saveTaxProfile() {
        val form = TaxProfileForm(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            texts = mapOf(
                TaxProfileField.NAME to "Boutique Umuco SPRL",
                TaxProfileField.TIN to "4000000001",
                TaxProfileField.TRADE_NUMBER to "RC 12345",
                TaxProfileField.PHONE_NUMBER to "+257 22 22 22 22",
                TaxProfileField.PROVINCE to "Bujumbura Mairie",
                TaxProfileField.COMMUNE to "Mukaza",
                TaxProfileField.QUARTIER to "Rohero",
                TaxProfileField.AVENUE to "Avenue de l'Industrie",
                TaxProfileField.ACTIVITY_SECTOR to "Commerce general",
                TaxProfileField.LEGAL_FORM to "SPRL",
            ),
            taxes = TaxRegistrations(vat = true),
            fiscalCenter = FiscalCenter.DMC,
        )
        val profile = assertIs<TaxProfileCheck.Valid>(form.check()).profile
        runBlocking { profiles.append(TaxProfileVersion(1, profile, clock.now(), alice.id)) }
    }

    @Test
    fun `the launch asks for the PIN and shows neither the assistant nor the home screen`() {
        composeRule.waitForText("Hello, Alice").assertIsDisplayed()

        composeRule.onNodeWithText("Enter your PIN to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Create the administrator account").assertDoesNotExist()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
        composeRule.onNodeWithText("Log out").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the login screen is translated in French`() {
        composeRule.waitForText("Bonjour, Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Saisissez votre code PIN pour continuer").assertIsDisplayed()
        composeRule.onNodeWithText("Code PIN oublié ?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Se connecter").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Effacer le dernier chiffre").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the login screen shows the name of the application until the tax profile exists`() {
        composeRule.waitForText("Hello, Alice")

        composeRule.onNodeWithText("EasyBilling").assertIsDisplayed()
        composeRule.onNodeWithText("TIN", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("EBMS", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the login screen shows the business and its TIN once the tax profile exists`() {
        saveTaxProfile()
        relaunch()

        composeRule.waitForText("Boutique Umuco SPRL").assertIsDisplayed()
        composeRule.onNodeWithText("TIN 4000000001").assertIsDisplayed()
        composeRule.onNodeWithText("Hello, Alice").assertIsDisplayed()
    }

    @Test
    fun `the login screen shows the EBMS environment once a connection to it succeeded`() {
        runBlocking {
            ebmsSettings.write(
                ebmsSettings.read().copy(lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to clock.now())),
            )
        }
        relaunch()

        composeRule.waitForText("EBMS Test").assertIsDisplayed()
    }

    @Test
    fun `the right PIN opens the home screen`() {
        logIn("123456")

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
        composeRule.onNodeWithText("EasyBilling").assertIsDisplayed()
        composeRule.onNodeWithText("The application is ready. Its features will arrive step by step.")
            .assertIsDisplayed()
    }

    @Test
    fun `the PIN is never written on the screen`() {
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin("123456")

        composeRule.onNodeWithContentDescription("6 digits entered").assertIsDisplayed()
        composeRule.onNodeWithText("123456").assertDoesNotExist()
    }

    @Test
    fun `the delete key corrects a digit before logging in`() {
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin("1234567")
        composeRule.pressKey("Delete the last digit")
        composeRule.pressKey("Log in")

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the home screen is translated in French`() {
        composeRule.waitForText("Saisissez votre code PIN pour continuer")
        composeRule.enterPin("123456")
        composeRule.pressKey("Se connecter")

        composeRule.waitForText("Bienvenue, Alice").assertIsDisplayed()
        composeRule.onNodeWithText("EasyBilling").assertIsDisplayed()
        composeRule.onNodeWithText("L'application est prête. Ses fonctionnalités arriveront étape par étape.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Se déconnecter").assertIsDisplayed()
    }

    @Test
    fun `a wrong PIN is refused with the attempts left`() {
        logIn("000000")

        composeRule.waitForText("Wrong PIN. 9 attempts left.").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("0 digits entered").assertIsDisplayed()
    }

    @Test
    fun `the ninth wrong PIN announces the last attempt`() {
        failLogin(times = 9)

        composeRule.onNodeWithText("Wrong PIN. 1 attempt left.").assertIsDisplayed()
    }

    @Test
    fun `ten wrong PINs lock the account for one minute`() {
        failLogin(times = 10)

        composeRule.onNodeWithText(LOCKED_FOR_60_SECONDS).assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Delete the last digit").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Log in").assertIsNotEnabled()
        assertEquals("ACCOUNT_LOCKED", auditedActions().last())
    }

    @Test
    fun `the countdown follows the clock and the account unlocks after one minute`() {
        failLogin(times = 10)

        clock.advance(Duration.ofSeconds(45))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForText("Account locked after too many attempts. Try again in 15 seconds.").assertIsDisplayed()

        clock.advance(Duration.ofSeconds(15))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1").assertIsEnabled()

        logIn("123456")
        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `a lock is still there when the application is opened again`() {
        failLogin(times = 10)

        relaunch()

        composeRule.waitForText(LOCKED_FOR_60_SECONDS).assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsNotEnabled()
    }

    @Test
    fun `logging out returns to the login screen and hides the home screen`() {
        logIn("123456")
        composeRule.waitForText("Welcome, Alice")

        composeRule.press("Log out")

        composeRule.waitForText("Enter your PIN to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
        waitForAudit("SESSION_CLOSED")
        assertEquals(listOf("ADMINISTRATOR_CREATED", "SESSION_OPENED", "SESSION_CLOSED"), auditedActions())
    }

    @Test
    fun `five minutes without action log the user out`() {
        logIn("123456")
        composeRule.waitForText("Welcome, Alice")

        clock.advance(Duration.ofMinutes(5))
        composeRule.mainClock.advanceTimeBy(10_000)

        composeRule.waitForText("Enter your PIN to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
        waitForAudit("SESSION_CLOSED")
        assertEquals("reason=INACTIVITY", runBlocking { auditStore.readAll().last().details })
    }

    @Test
    fun `an action on the screen postpones the automatic logout`() {
        logIn("123456")
        composeRule.waitForText("Welcome, Alice")

        clock.advance(Duration.ofMinutes(4))
        composeRule.onNodeWithText("Welcome, Alice").performClick()
        clock.advance(Duration.ofMinutes(4))
        composeRule.mainClock.advanceTimeBy(10_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `a forgotten PIN is replaced with the recovery code`() {
        resetPin(recoveryCode = "abcd-efgh-jklm")

        composeRule.waitForText("NPQR-STUV-WXYZ").assertIsDisplayed()
        composeRule.pressInForm("I have written it down")

        logIn("123456")
        composeRule.waitForText("Wrong PIN. 9 attempts left.")
        logIn("987654")
        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `a wrong recovery code is refused with the attempts left`() {
        resetPin(recoveryCode = "WRONG-CODE-0000")

        composeRule.waitForText("Wrong recovery code. 9 attempts left.").assertIsDisplayed()
        composeRule.onNodeWithText("Recovery code").assertIsDisplayed()
    }

    @Test
    fun `after a wrong recovery code only the code is typed again`() {
        resetPin(recoveryCode = "WRONG-CODE-0000")
        composeRule.waitForText("Wrong recovery code. 9 attempts left.")

        composeRule.replaceInForm("Recovery code", "abcd-efgh-jklm")
        composeRule.pressInForm("Continue")
        composeRule.waitForText("New PIN")
        composeRule.onNodeWithContentDescription("6 digits entered").assertIsDisplayed()
        composeRule.pressKey("Continue")
        composeRule.waitForText("Confirm the PIN")
        composeRule.onNodeWithContentDescription("6 digits entered").assertIsDisplayed()
        composeRule.pressKey("Change the PIN")

        composeRule.waitForText("NPQR-STUV-WXYZ").assertIsDisplayed()
    }

    @Test
    fun `a different confirmation of the new PIN is refused without spending an attempt`() {
        resetPin(recoveryCode = "abcd-efgh-jklm", pin = "987654", confirmation = "987650")

        composeRule.waitForText("The two PINs are different").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("0 digits entered").assertIsDisplayed()
        assertEquals(listOf("ADMINISTRATOR_CREATED"), auditedActions())
    }

    @Test
    fun `the back arrow of the new PIN screen returns to the login screen`() {
        composeRule.waitForText("Forgot your PIN?").performScrollTo().performClick()
        composeRule.waitForText("Choose a new PIN")

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("Enter your PIN to continue").assertIsDisplayed()
    }

    @Test
    fun `the back arrow of a step of the new PIN screen returns to the previous step`() {
        composeRule.waitForText("Forgot your PIN?").performScrollTo().performClick()
        composeRule.waitForText("Choose a new PIN")
        composeRule.type("Recovery code", "abcd-efgh-jklm")
        composeRule.pressInForm("Continue")
        composeRule.waitForText("New PIN")

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("Recovery code").assertIsDisplayed()
        composeRule.onNodeWithText("Enter your PIN to continue").assertDoesNotExist()
    }

    @Test
    fun `the audit log stays intact through logins, failures and a lock`() {
        failLogin(times = 10)

        assertEquals(
            listOf("ADMINISTRATOR_CREATED") + List(10) { "PIN_REJECTED" } + "ACCOUNT_LOCKED",
            auditedActions(),
        )
        assertEquals(AuditVerification.Intact, runBlocking { auditLog.verify() })
    }

    private companion object {
        const val LOCKED_FOR_60_SECONDS = "Account locked after too many attempts. Try again in 60 seconds."
    }
}
