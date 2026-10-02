package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Behaviour of the loading, empty and error state components. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class StateComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `loading state shows the default message`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { EasyBillingTheme { LoadingState() } }

        composeRule.onNodeWithText("Loading…").assertIsDisplayed()
    }

    @Test
    fun `loading state shows a custom message`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { EasyBillingTheme { LoadingState(message = "Sending the invoice…") } }

        composeRule.onNodeWithText("Sending the invoice…").assertIsDisplayed()
    }

    @Test
    fun `empty state shows its title and message`() {
        composeRule.setContent {
            EasyBillingTheme { EmptyState(title = "No invoice", message = "Issued invoices appear here.") }
        }

        composeRule.onNodeWithText("No invoice").assertIsDisplayed()
        composeRule.onNodeWithText("Issued invoices appear here.").assertIsDisplayed()
    }

    @Test
    fun `empty state action invokes its callback`() {
        var clicks = 0
        composeRule.setContent {
            EasyBillingTheme {
                EmptyState(title = "No invoice", action = StateAction("New sale") { clicks++ })
            }
        }

        composeRule.onNodeWithText("New sale").assertHasClickAction().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `error state shows the default title and the message`() {
        composeRule.setContent { EasyBillingTheme { ErrorState(message = "The server did not answer.") } }

        composeRule.onNodeWithText("Something went wrong").assertIsDisplayed()
        composeRule.onNodeWithText("The server did not answer.").assertIsDisplayed()
    }

    @Test
    fun `error state retry invokes its callback`() {
        var retries = 0
        composeRule.setContent {
            EasyBillingTheme { ErrorState(message = "The server did not answer.", onRetry = { retries++ }) }
        }

        composeRule.onNodeWithText("Try again").assertHasClickAction().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `error state has no retry button without a retry callback`() {
        composeRule.setContent { EasyBillingTheme { ErrorState(message = "The server did not answer.") } }

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `default texts are translated in French`() {
        composeRule.setContent {
            EasyBillingTheme { ErrorState(message = "Le serveur n'a pas répondu.", onRetry = {}) }
        }

        composeRule.onNodeWithText("Une erreur est survenue").assertIsDisplayed()
        composeRule.onNodeWithText("Réessayer").assertIsDisplayed()
    }
}
