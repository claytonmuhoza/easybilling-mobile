package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the navigation between the main screens: a bar on a phone, a rail on a tablet. */
@RunWith(AndroidJUnit4::class)
class NavigationFrameTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected by mutableIntStateOf(0)

    private fun show() {
        composeRule.setContent {
            EasyBillingTheme {
                NavigationFrame(
                    entries = listOf(
                        NavigationEntry(AppIcon.STORE, "Register"),
                        NavigationEntry(AppIcon.INVOICE, "Invoices"),
                    ),
                    selected = selected,
                    onSelect = { selected = it },
                ) {
                    Text("Screen $selected")
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "en-w360dp-h640dp")
    fun `a phone has a bar, which says which screen is shown and opens the other`() {
        show()

        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(NavigationFrameTags.RAIL).assertDoesNotExist()
        composeRule.onNodeWithText("Register").assertIsSelected()
        composeRule.onNodeWithText("Invoices").assertIsNotSelected()
        composeRule.onNodeWithText("Screen 0").assertIsDisplayed()

        composeRule.onNodeWithText("Invoices").performClick()

        assertEquals(1, selected)
        composeRule.onNodeWithText("Invoices").assertIsSelected()
        composeRule.onNodeWithText("Screen 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp")
    fun `a tablet has a rail instead of the bar`() {
        show()

        composeRule.onNodeWithTag(NavigationFrameTags.RAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertDoesNotExist()
        composeRule.onNodeWithText("Register").assertIsSelected()

        composeRule.onNodeWithText("Invoices").performClick()

        assertEquals(1, selected)
        composeRule.onNodeWithText("Screen 1").assertIsDisplayed()
    }
}
