package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the counted text field, the choice field, the switch row and the form actions. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class InputComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var text by mutableStateOf("")

    private fun showCountedField(errorMessage: String? = null, hint: String? = null) {
        composeRule.setContent {
            EasyBillingTheme {
                CountedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Phone",
                    maxLength = 20,
                    errorMessage = errorMessage,
                    hint = hint,
                )
            }
        }
    }

    @Test
    fun `the counted field shows how many characters are used`() {
        showCountedField()

        composeRule.onNodeWithText("Phone").performTextInput("+257 22")

        composeRule.onNodeWithText("7 / 20").assertIsDisplayed()
    }

    @Test
    fun `the counted field lets the user type beyond the limit, so the refusal can be explained`() {
        showCountedField()

        composeRule.onNodeWithText("Phone").performTextInput("x".repeat(25))

        assertEquals(25, text.length)
        composeRule.onNodeWithText("25 / 20").assertIsDisplayed()
    }

    @Test
    fun `the counted field shows its hint, or its error instead`() {
        showCountedField(hint = "Optional")
        composeRule.onNodeWithText("Optional").assertIsDisplayed()
    }

    @Test
    fun `the error of the counted field replaces its hint`() {
        showCountedField(errorMessage = "Required", hint = "Optional")

        composeRule.onNodeWithText("Required").assertIsDisplayed()
        composeRule.onNodeWithText("Optional").assertDoesNotExist()
    }

    private enum class Size { SMALL, LARGE }

    @Test
    fun `the choice field selects one option`() {
        var selected by mutableStateOf<Size?>(null)
        composeRule.setContent {
            EasyBillingTheme {
                ChoiceField(
                    label = "Size",
                    options = listOf(Size.SMALL to "Small", Size.LARGE to "Large"),
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Large").performClick()

        assertEquals(Size.LARGE, selected)
        composeRule.onNodeWithText("Large").assertIsSelected()
        composeRule.onNodeWithText("Small").assertIsNotSelected()
    }

    @Test
    fun `the choice field shows its error`() {
        composeRule.setContent {
            EasyBillingTheme {
                ChoiceField(
                    label = "Size",
                    options = listOf(Size.SMALL to "Small"),
                    selected = null,
                    onSelect = {},
                    errorMessage = "Choose a size",
                )
            }
        }

        composeRule.onNodeWithText("Choose a size").assertIsDisplayed()
    }

    @Test
    fun `a tap anywhere on the switch row toggles it`() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            EasyBillingTheme {
                SwitchRow(label = "Registered for VAT", checked = checked, onCheckedChange = { checked = it })
            }
        }

        composeRule.onNodeWithText("Registered for VAT").assertIsOff().performClick()

        assertEquals(true, checked)
        composeRule.onNodeWithText("Registered for VAT").assertIsOn()
    }

    @Test
    fun `the form screen shows its actions`() {
        var clicks = 0
        composeRule.setContent {
            EasyBillingTheme {
                FormScreen(title = "Profile", actions = { TextAction(label = "History", onClick = { clicks++ }) }) {
                    SectionHeader("Address")
                    Text("Form content")
                }
            }
        }

        composeRule.onNodeWithText("Address").assertIsDisplayed()
        composeRule.onNodeWithText("History").performClick()

        assertEquals(1, clicks)
    }
}
