package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.press
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

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun createAliceThenLaunch() {
        hiltRule.inject()
        assertIs<CreateAdministrator.Result.Created>(
            runBlocking { createAdministrator("Alice", "123456", "123456") },
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() = scenario.close()

    private fun logIn(pin: String) {
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.type("PIN", pin)
        composeRule.press("Log in")
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
        composeRule.onNodeWithText("Code PIN oublié ?").assertIsDisplayed()
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
    @Config(qualifiers = "fr")
    fun `the home screen is translated in French`() {
        composeRule.waitForText("Saisissez votre code PIN pour continuer")
        composeRule.type("Code PIN", "123456")
        composeRule.press("Se connecter")

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
        composeRule.onNodeWithText("PIN").assertIsNotEnabled()
        composeRule.onNodeWithText("Log in").assertIsNotEnabled()
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
        composeRule.onNodeWithText("PIN").assertIsEnabled()

        logIn("123456")
        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `a lock is still there when the application is opened again`() {
        failLogin(times = 10)

        // A new activity has new view models: the lock can only come from the database.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.waitForText(LOCKED_FOR_60_SECONDS).assertIsDisplayed()
        composeRule.onNodeWithText("PIN").assertIsNotEnabled()
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
        composeRule.waitForText("Forgot your PIN?").performClick()
        composeRule.waitForText("Choose a new PIN")
        composeRule.type("Recovery code", "abcd-efgh-jklm")
        composeRule.type("New PIN", "987654")
        composeRule.type("Confirm the PIN", "987654")
        composeRule.press("Change the PIN")

        composeRule.waitForText("NPQR-STUV-WXYZ").assertIsDisplayed()
        composeRule.press("I have written it down")

        logIn("123456")
        composeRule.waitForText("Wrong PIN. 9 attempts left.")
        logIn("987654")
        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `a wrong recovery code is refused with the attempts left`() {
        composeRule.waitForText("Forgot your PIN?").performClick()
        composeRule.waitForText("Choose a new PIN")
        composeRule.type("Recovery code", "WRONG-CODE-0000")
        composeRule.type("New PIN", "987654")
        composeRule.type("Confirm the PIN", "987654")
        composeRule.press("Change the PIN")

        composeRule.waitForText("Wrong recovery code. 9 attempts left.").assertIsDisplayed()
    }

    @Test
    fun `the back arrow of the new PIN screen returns to the login screen`() {
        composeRule.waitForText("Forgot your PIN?").performClick()
        composeRule.waitForText("Choose a new PIN")

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("Enter your PIN to continue").assertIsDisplayed()
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
