package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the PIN field and of the form screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class FormComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var pin by mutableStateOf("")

    private fun showPinField(errorMessage: String? = null, enabled: Boolean = true) {
        composeRule.setContent {
            EasyBillingTheme {
                PinField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = "PIN",
                    maxLength = 6,
                    errorMessage = errorMessage,
                    supportingText = "6 digits",
                    enabled = enabled,
                )
            }
        }
    }

    @Test
    fun `the PIN field keeps digits only`() {
        showPinField()

        composeRule.onNodeWithText("PIN").performTextInput("12a3 4-5")

        assertEquals("12345", pin)
    }

    @Test
    fun `the PIN field stops at its maximum length`() {
        showPinField()

        composeRule.onNodeWithText("PIN").performTextInput("123456789")

        assertEquals("123456", pin)
    }

    @Test
    fun `the PIN is masked on screen`() {
        showPinField()

        composeRule.onNodeWithText("PIN").performTextInput("123456")

        // EditableText is what the field draws; the typed value itself stays in InputText.
        composeRule.onNodeWithText("PIN")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("••••••")))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun `the PIN field shows its hint`() {
        showPinField()

        composeRule.onNodeWithText("6 digits").assertIsDisplayed()
    }

    @Test
    fun `the PIN field shows its error instead of the hint`() {
        showPinField(errorMessage = "Wrong PIN")

        composeRule.onNodeWithText("Wrong PIN").assertIsDisplayed()
        composeRule.onNodeWithText("6 digits").assertDoesNotExist()
    }

    @Test
    fun `a disabled PIN field refuses input`() {
        showPinField(enabled = false)

        composeRule.onNodeWithText("PIN").assertIsNotEnabled()
    }

    @Test
    fun `the form screen shows its title and content`() {
        composeRule.setContent {
            EasyBillingTheme { FormScreen(title = "New account") { Text("Form content") } }
        }

        composeRule.onNodeWithText("New account").assertIsDisplayed()
        composeRule.onNodeWithText("Form content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `the back button of the form screen invokes its callback`() {
        var backs = 0
        composeRule.setContent {
            EasyBillingTheme { FormScreen(title = "New PIN", onBack = { backs++ }) { Text("Form content") } }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
