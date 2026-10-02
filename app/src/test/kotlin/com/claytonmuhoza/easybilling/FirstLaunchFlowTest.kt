package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.testing.press
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

    private fun fillForm(name: String = "Alice", pin: String = "123456", confirmation: String = pin) {
        composeRule.waitForText("Create the administrator account")
        composeRule.type("Name", name)
        composeRule.type("PIN", pin)
        composeRule.type("Confirm the PIN", confirmation)
        composeRule.press("Create the account")
    }

    @Test
    fun `the first launch shows the assistant in English`() {
        composeRule.waitForText("Create the administrator account").assertIsDisplayed()
        composeRule.onNodeWithText("6 to 12 digits").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the first launch shows the assistant in French`() {
        composeRule.waitForText("Créer le compte administrateur").assertIsDisplayed()
        composeRule.onNodeWithText("6 à 12 chiffres").assertIsDisplayed()
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
        fillForm(name = "  ")

        composeRule.waitForText("Enter a name").assertIsDisplayed()
        assertEquals(0, runBlocking { users.count() })
    }

    @Test
    fun `a PIN that is too short is refused`() {
        fillForm(pin = "12345")

        composeRule.waitForText("The PIN has at least 6 digits").assertIsDisplayed()
        assertEquals(0, runBlocking { users.count() })
    }

    @Test
    fun `a different confirmation is refused`() {
        fillForm(pin = "123456", confirmation = "123457")

        composeRule.waitForText("The two PINs are different").assertIsDisplayed()
        assertEquals(0, runBlocking { users.count() })
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
    fun `acknowledging the recovery code opens the home screen`() {
        fillForm()
        composeRule.waitForText("ABCD-EFGH-JKLM")

        composeRule.press("I have written it down")

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Log out").assertIsDisplayed()
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
