package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationFrameTags
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.pressInForm
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
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The navigation between the main screens and the way to the settings, by the administrator
 * "Alice", already logged in, in a new installation.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class NavigationFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun logInAlice() {
        hiltRule.inject()
        runBlocking {
            val created = createAdministrator("Alice", "123456", "123456")
            val alice = assertIs<CreateAdministrator.Result.Created>(created).user
            sessions.open(alice)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Welcome, Alice")
    }

    @After
    fun stop() = scenario.close()

    private fun tab(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun pressBack() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun barExists() = composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertExists()

    private fun noNavigation() {
        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(NavigationFrameTags.RAIL).assertDoesNotExist()
    }

    @Test
    fun `a phone has a bar with the register and the invoices, and back returns to the register`() {
        barExists()
        composeRule.onNodeWithTag(NavigationFrameTags.RAIL).assertDoesNotExist()
        tab("Register").assertIsSelected()
        composeRule.waitForText("New sale").assertIsDisplayed()

        tab("Invoices").performClick()

        composeRule.waitForText("No invoice yet").assertIsDisplayed()
        tab("Invoices").assertIsSelected()
        barExists()
        // A main screen is not left by an arrow.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()

        pressBack()

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
        tab("Register").assertIsSelected()
        composeRule.waitForText("New sale").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w800dp-h1280dp")
    fun `a tablet has a rail instead, and new sale is a card`() {
        composeRule.onNodeWithTag(NavigationFrameTags.RAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertDoesNotExist()
        tab("Register").assertIsSelected()
        composeRule.waitForText("New sale").assertIsDisplayed()
        composeRule.waitForText("Open the cash register").assertIsDisplayed()

        tab("Invoices").performClick()

        composeRule.waitForText("No invoice yet").assertIsDisplayed()
        tab("Invoices").assertIsSelected()
        tab("Register").performClick()
        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
    }

    @Test
    fun `the home screen holds no setting, and says what is left to configure`() {
        composeRule.waitForText("Cash register locked: finish the steps marked “To do”.").assertIsDisplayed()
        for (setting in listOf("Tax profile", "EBMS connection", "Taxes", "Numbering", "Log out")) {
            assertEquals(0, composeRule.onAllNodesWithTextCount(setting), setting)
        }
        composeRule.waitForText("Items").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("Customers").performScrollTo().assertIsDisplayed()

        composeRule.pressInForm("Open the settings")

        composeRule.waitForText("Before the first invoice").assertIsDisplayed()
    }

    @Test
    fun `the settings hold the four steps and the logout, without the navigation`() {
        composeRule.openSettings()

        noNavigation()
        for (setting in listOf("Tax profile", "EBMS connection", "Taxes", "Numbering")) {
            composeRule.waitForText(setting).performScrollTo().assertIsDisplayed()
        }
        assertEquals(3, composeRule.onAllNodesWithTextCount("To do"))
        composeRule.waitForText("Logged in as Alice").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("Welcome, Alice").assertIsDisplayed()
        barExists()
    }

    @Test
    fun `a setting opens from the settings and comes back to them`() {
        composeRule.openSettings()
        composeRule.waitForText("Numbering").performScrollTo().performClick()
        composeRule.waitForText("Next numbers")
        noNavigation()

        pressBack()

        // The settings are where they were left, scrolled to the setting that was opened.
        composeRule.waitForText("Before the first invoice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `logging out from the settings returns to the login screen`() {
        composeRule.openSettings()

        composeRule.pressInForm("Log out")

        composeRule.waitForText("Enter your PIN to continue").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }
}
