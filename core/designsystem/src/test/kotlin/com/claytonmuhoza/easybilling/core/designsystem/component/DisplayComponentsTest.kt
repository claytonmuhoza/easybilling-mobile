package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the status chip, the amount, the cards, the quantity stepper and the search field. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class DisplayComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { EasyBillingTheme { content() } }
    }

    @Test
    fun `a status is told by words, with a different icon for each tone`() {
        show { StatusChip(StatusTone.PENDING, "Awaiting transmission") }

        composeRule.onNodeWithText("Awaiting transmission").assertIsDisplayed()
        assertEquals(StatusTone.entries.size, StatusTone.entries.map { it.icon() }.toSet().size)
    }

    @Test
    fun `a status banner says its sentence`() {
        show { StatusBanner(StatusTone.SUCCESS, "TIN recognised by the OBR: NDIKUMANA Jean Marie") }

        composeRule.onNodeWithText("TIN recognised by the OBR: NDIKUMANA Jean Marie").assertIsDisplayed()
    }

    @Test
    fun `an amount and its currency are one text`() {
        show { AmountText(amount = "146,566", currency = "BIF") }

        composeRule.onNodeWithText("146,566 BIF").assertIsDisplayed()
    }

    @Test
    fun `the stepper adds one and takes one off, and says what each button does`() {
        var quantity by mutableStateOf(2)
        show {
            QuantityStepper(
                quantity = quantity.toString(),
                onLess = { quantity-- },
                onMore = { quantity++ },
                lessDescription = "One less: soap",
                moreDescription = "One more: soap",
            )
        }

        composeRule.onNodeWithContentDescription("One more: soap").performClick()
        composeRule.onNodeWithText("3").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("One less: soap").performClick()
        composeRule.onNodeWithContentDescription("One less: soap").performClick()
        composeRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun `a disabled stepper changes nothing`() {
        var changes = 0
        show {
            QuantityStepper("2", { changes++ }, { changes++ }, "One less", "One more", enabled = false)
        }

        composeRule.onNodeWithContentDescription("One more").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("One less").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("One more").performClick()
        assertEquals(0, changes)
    }

    @Test
    fun `the search field keeps its label once something is typed, and is submitted by the keyboard`() {
        var query by mutableStateOf("")
        var submitted = 0
        show { SearchField(query, { query = it }, label = "Search an item", onSubmit = { submitted++ }) }

        composeRule.onNodeWithText("Search an item").performTextInput("6001234567890")
        composeRule.onNodeWithText("Search an item").performImeAction()

        assertEquals("6001234567890", query)
        assertEquals(1, submitted)
    }

    @Test
    fun `a navigation row shows its texts and opens what it is about`() {
        var opened = 0
        show {
            NavigationRow(
                title = "Taxes",
                onClick = { opened++ },
                supporting = "2 active taxes",
                icon = AppIcon.TAG,
                trailing = { StatusChip(StatusTone.SUCCESS, "Done") },
            )
        }

        composeRule.onNodeWithText("2 active taxes").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Taxes").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a tonal card is pressed only when it has an action`() {
        var pressed = 0
        show {
            TonalCard(onClick = { pressed++ }) { Text("Pressable") }
            TonalCard { Text("Plain") }
        }

        composeRule.onNodeWithText("Pressable").performClick()
        composeRule.onNodeWithText("Plain").performClick()

        assertEquals(1, pressed)
    }
}
