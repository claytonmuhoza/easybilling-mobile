package com.claytonmuhoza.easybilling

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.testing.enterPin
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressKey
import com.claytonmuhoza.easybilling.testing.type
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals

/** First launch (UC-A01): the device has no account, so the assistant creates the administrator. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class FirstLaunchFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var users: UserRepository

    @Inject
    lateinit var auditLog: AuditLog

    @Before
    fun inject() = hiltRule.inject()

    /** First step of the assistant: the name, typed with the keyboard. */
    private fun enterName(name: String = "Alice") {
        composeRule.waitForText("Create the administrator account")
        composeRule.type("Name", name)
        composeRule.pressInForm("Continue")
    }

    /** Second step: the PIN, typed on the PIN pad. */
    private fun choosePin(pin: String = "123456") {
        composeRule.waitForText("Choose your PIN")
        composeRule.enterPin(pin)
        composeRule.pressKey("Continue")
    }

    /** Third step: the PIN a second time, typed on the PIN pad. */
    private fun confirmPin(pin: String = "123456") {
        composeRule.waitForText("Confirm the PIN")
        composeRule.enterPin(pin)
        composeRule.pressKey("Create the account")
    }

    private fun fillForm(name: String = "Alice", pin: String = "123456", confirmation: String = pin) {
        enterName(name)
        choosePin(pin)
        confirmPin(confirmation)
    }

    @Test
    fun `the first launch shows the assistant in English`() {
        composeRule.waitForText("Create the administrator account").assertIsDisplayed()

        enterName()
        composeRule.waitForText("6 to 12 digits").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the first launch shows the assistant in French`() {
        composeRule.waitForText("Créer le compte administrateur").assertIsDisplayed()

        composeRule.type("Nom", "Alice")
        composeRule.pressInForm("Continuer")
        composeRule.waitForText("6 à 12 chiffres").assertIsDisplayed()
        composeRule.onNodeWithText("Choisissez votre code PIN").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "rn")
    fun `the first launch falls back to English for an unsupported language`() {
        composeRule.waitForText("Create the administrator account").assertIsDisplayed()
    }

    @Test
    fun `the home screen is not reachable before the account exists`() {
        composeRule.waitForText("Create the administrator account")

        composeRule.onNodeWithText("Log out").assertDoesNotExist()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
    }

    @Test
    fun `a blank name is refused`() {
        enterName("  ")

        composeRule.waitForText("Enter a name").assertIsDisplayed()
        composeRule.onNodeWithText("Choose your PIN").assertDoesNotExist()
        assertEquals(0, runBlocking { users.count() })
    }

    @Test
    fun `a PIN that is too short is refused`() {
        enterName()
        composeRule.waitForText("Choose your PIN")
        composeRule.enterPin("12345")
        composeRule.pressKey("Continue")

        composeRule.waitForText("The PIN has at least 6 digits").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm the PIN").assertDoesNotExist()
        assertEquals(0, runBlocking { users.count() })
    }

    @Test
    fun `a different confirmation is refused`() {
        fillForm(pin = "123456", confirmation = "123457")

        composeRule.waitForText("The two PINs are different").assertIsDisplayed()
        assertEquals(0, runBlocking { users.count() })
    }

    @Test
    fun `a refused confirmation is typed again and creates the account`() {
        fillForm(pin = "123456", confirmation = "123457")
        composeRule.waitForText("The two PINs are different")
        composeRule.onNodeWithContentDescription("0 digits entered").assertIsDisplayed()

        confirmPin("123456")

        composeRule.waitForText("ABCD-EFGH-JKLM").assertIsDisplayed()
    }

    @Test
    fun `the back arrow returns to the previous step and keeps what was typed`() {
        enterName()
        choosePin()
        composeRule.waitForText("Confirm the PIN")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForText("Choose your PIN")
        composeRule.onNodeWithContentDescription("6 digits entered").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForText("Alice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `the PIN is never written on the screen`() {
        enterName()
        composeRule.waitForText("Choose your PIN")
        composeRule.enterPin("123456")

        composeRule.onNodeWithContentDescription("6 digits entered").assertIsDisplayed()
        composeRule.onNodeWithText("123456").assertDoesNotExist()
    }

    @Test
    fun `creating the account shows the recovery code before anything else`() {
        fillForm()

        composeRule.waitForText("ABCD-EFGH-JKLM").assertIsDisplayed()
        composeRule.onNodeWithText("Your recovery code").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome, Alice").assertDoesNotExist()
        assertEquals(listOf("Alice"), runBlocking { users.findAll().map { it.name } })
    }

    @Test
    fun `the recovery code is copied to the clipboard as a sensitive content`() {
        fillForm()
        composeRule.waitForText("ABCD-EFGH-JKLM")
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)

        composeRule.pressInForm("Copy the code")

        composeRule.waitForText("Code copied").assertIsDisplayed()
        val clip = checkNotNull(clipboard.primaryClip)
        assertEquals("ABCD-EFGH-JKLM", clip.getItemAt(0).text.toString())
        assertEquals(true, clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun `acknowledging the recovery code opens the home screen`() {
        fillForm()
        composeRule.waitForText("ABCD-EFGH-JKLM")

        composeRule.pressInForm("I have written it down")

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
        // The settings, where the logout is, are one touch away.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `the PIN and the recovery code are not stored in clear and the audit log is intact`() {
        fillForm()
        composeRule.waitForText("ABCD-EFGH-JKLM")

        val alice = runBlocking { users.findAll().single() }
        val stored = with(alice.credentials) { pinHash.salt + pinHash.hash + recoveryHash.salt + recoveryHash.hash }
        val storedText = String(stored, Charsets.ISO_8859_1)
        assertEquals(false, "123456" in storedText)
        assertEquals(false, "ABCDEFGHJKLM" in storedText)
        assertEquals(AuditVerification.Intact, runBlocking { auditLog.verify() })
    }
}
