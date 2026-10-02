package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test

/** The application starts on the home screen, in each supported language. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "en")
    fun `launch shows the home screen in English`() {
        composeRule.onNodeWithText("EasyBilling").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
        composeRule.onNodeWithText("The application is ready. Its features will arrive step by step.")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `launch shows the home screen in French`() {
        composeRule.onNodeWithText("EasyBilling").assertIsDisplayed()
        composeRule.onNodeWithText("Bienvenue").assertIsDisplayed()
        composeRule.onNodeWithText("L'application est prête. Ses fonctionnalités arriveront étape par étape.")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "rn")
    fun `launch falls back to English for an unsupported language`() {
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
    }
}
