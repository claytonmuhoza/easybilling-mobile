package com.claytonmuhoza.easybilling.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput

private const val TIMEOUT_MILLIS = 10_000L

/** Waits for work done off the main thread (database, PIN derivation, network) to show [text]. */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false): SemanticsNodeInteraction {
    waitUntil(TIMEOUT_MILLIS) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithText(text, substring = substring)
}

/** Types [value] in the field labelled [label]. */
fun ComposeTestRule.type(label: String, value: String) {
    onNodeWithText(label).performTextInput(value)
}

/** Presses the button showing [text]. */
fun ComposeTestRule.press(text: String) {
    onNodeWithText(text).performClick()
}

/** Scrolls a long form to the field labelled [label], then types [value] in it. */
fun ComposeTestRule.typeInForm(label: String, value: String) {
    onNodeWithText(label).performScrollTo().performTextInput(value)
}

/** Scrolls a long form to the field labelled [label], then replaces its content with [value]. */
fun ComposeTestRule.replaceInForm(label: String, value: String) {
    onNodeWithText(label).performScrollTo().performTextClearance()
    onNodeWithText(label).performTextInput(value)
}

/** Scrolls a long form to the element showing [text], then presses it. */
fun ComposeTestRule.pressInForm(text: String) {
    onNodeWithText(text).performScrollTo().performClick()
}

/** @return how many elements show [text]. */
fun ComposeTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false): Int =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
