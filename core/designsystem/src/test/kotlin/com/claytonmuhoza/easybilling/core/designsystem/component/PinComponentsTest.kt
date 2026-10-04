package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the on-screen PIN pad, of the PIN dots, of the avatar and of the entry screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class PinComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var pin by mutableStateOf("")
    private var submitted = 0

    private fun showPinEntry(message: String? = null, isError: Boolean = false, enabled: Boolean = true) {
        composeRule.setContent {
            EasyBillingTheme {
                PinEntry(
                    pin = pin,
                    onPinChange = { pin = it },
                    maxLength = 12,
                    onSubmit = { submitted++ },
                    submitDescription = "Log in",
                    slots = 6,
                    message = message,
                    isError = isError,
                    enabled = enabled,
                )
            }
        }
    }

    private fun pressKeys(digits: String) {
        digits.forEach { composeRule.onNodeWithText(it.toString()).performClick() }
    }

    @Test
    fun `each key types its digit, in the order pressed`() {
        showPinEntry()

        pressKeys("1234567890")

        assertEquals("1234567890", pin)
    }

    @Test
    fun `typing stops at the longest PIN`() {
        showPinEntry()

        pressKeys("1234567890123")

        assertEquals("123456789012", pin)
    }

    @Test
    fun `the delete key takes the last digit off, and does nothing on an empty PIN`() {
        showPinEntry()
        pressKeys("123")

        composeRule.onNodeWithContentDescription("Delete the last digit").performClick()
        assertEquals("12", pin)

        repeat(3) { composeRule.onNodeWithContentDescription("Delete the last digit").performClick() }
        assertEquals("", pin)
    }

    @Test
    fun `the dots say how many digits are typed and never show them`() {
        showPinEntry()
        composeRule.onNodeWithContentDescription("0 digits entered").assertIsDisplayed()

        pressKeys("7")
        composeRule.onNodeWithContentDescription("1 digit entered").assertIsDisplayed()

        pressKeys("77")
        composeRule.onNodeWithContentDescription("3 digits entered").assertIsDisplayed()
        composeRule.onNodeWithText("777").assertDoesNotExist()
    }

    @Test
    fun `the submit key says what it does and submits`() {
        showPinEntry()
        pressKeys("123456")

        composeRule.onNodeWithContentDescription("Log in").performClick()

        assertEquals(1, submitted)
        assertEquals("123456", pin)
    }

    @Test
    fun `the message is shown under the dots`() {
        showPinEntry(message = "Wrong PIN. 9 attempts left.", isError = true)

        composeRule.onNodeWithText("Wrong PIN. 9 attempts left.").assertIsDisplayed()
    }

    @Test
    fun `a disabled pad types nothing, deletes nothing and submits nothing`() {
        pin = "123"
        showPinEntry(message = "Account locked", isError = true, enabled = false)

        composeRule.onNodeWithText("5").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Delete the last digit").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Log in").assertIsNotEnabled()
        pressKeys("5")
        composeRule.onNodeWithContentDescription("Delete the last digit").performClick()
        composeRule.onNodeWithContentDescription("Log in").performClick()

        assertEquals("123", pin)
        assertEquals(0, submitted)
        composeRule.onNodeWithText("Account locked").assertIsDisplayed()
    }

    @Test
    fun `the initials are the first letters of the first two words`() {
        assertEquals("A", initialsOf("Alice"))
        assertEquals("JC", initialsOf("jean claude Ndayishimiye"))
        assertEquals("AN", initialsOf("  Aline   N. "))
        assertEquals("?", initialsOf("   "))
    }

    @Test
    fun `the entry screen shows its two parts and no back arrow by default`() {
        composeRule.setContent {
            EasyBillingTheme {
                EntryScreen(header = { Text("Boutique Umuco") }) { Text("Enter your PIN") }
            }
        }

        composeRule.onNodeWithText("Boutique Umuco").assertIsDisplayed()
        composeRule.onNodeWithText("Enter your PIN").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp")
    fun `the entry screen shows its two parts side by side on a wide screen`() {
        composeRule.setContent {
            EasyBillingTheme {
                EntryScreen(header = { Text("Boutique Umuco") }) { Text("Enter your PIN") }
            }
        }

        val header = composeRule.onNodeWithText("Boutique Umuco").assertIsDisplayed().fetchSemanticsNode()
        val content = composeRule.onNodeWithText("Enter your PIN").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(true, header.boundsInRoot.right <= content.boundsInRoot.left)
    }

    @Test
    fun `the back arrow of the entry screen goes back`() {
        var backs = 0
        composeRule.setContent {
            EasyBillingTheme {
                EntryScreen(onBack = { backs++ }, header = { Text("New PIN") }) { Text("Content") }
            }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
