package com.claytonmuhoza.easybilling.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/**
 * Opens the settings from the home screen. Closing a setting comes back to the settings: when
 * they are already shown, nothing is pressed.
 *
 * The button is in the title bar, which does not move while the home screen loads: nothing of
 * the home screen is waited for, whatever its transmission banner says.
 *
 * @param settings the name of the settings, in the language of the test: the description of the
 * button on the home screen and the title of the settings screen.
 */
fun ComposeTestRule.openSettings(settings: String = "Settings") {
    fun onHome() = onAllNodesWithContentDescription(settings).fetchSemanticsNodes().isNotEmpty()
    waitUntil(TIMEOUT_MILLIS) { onHome() || onAllNodesWithText(settings).fetchSemanticsNodes().isNotEmpty() }
    if (onHome()) {
        onNodeWithContentDescription(settings).performClick()
        waitForText(settings)
    }
}

/**
 * Opens the settings and waits for what is left to do before the first invoice. It is read after
 * the screen appears: its sentence then pushes down everything under it, and a press made
 * meanwhile misses its button. The marks of the steps come with the sentence and, unlike it,
 * are not on the home screen.
 *
 * @param settings the name of the settings, in the language of the test.
 * @param toDo the mark of a step that is left to do, in the language of the test.
 */
fun ComposeTestRule.openLoadedSettings(settings: String = "Settings", toDo: String = "To do") {
    openSettings(settings)
    waitUntil(TIMEOUT_MILLIS) { onAllNodesWithTextCount(toDo) > 0 }
}

/**
 * Opens the settings from the home screen, then presses the row showing [text].
 *
 * @param text what the row shows.
 * @param settings the name of the settings, in the language of the test.
 */
fun ComposeTestRule.pressInSettings(text: String, settings: String = "Settings") {
    openSettings(settings)
    waitForText(text).performScrollTo().performClick()
}

/** What the screen of an issued invoice says of each status. */
object IssuedInvoiceStatus {
    const val PENDING = "Awaiting transmission to the OBR"
    const val REGISTERED = "Registered by the OBR"
    const val REJECTED = "Rejected by the OBR"
}

/**
 * Leaves the screen of an issued invoice for the home screen, once that screen shows [status].
 *
 * A test learns the status of an invoice from the storage before the screen has read it. The
 * screen then changes under the button (the acknowledgement adds rows), and a press made at that
 * moment is lost: the screen must say [status] before the button is pressed.
 *
 * @param status what the screen says of the status the invoice has, from [IssuedInvoiceStatus].
 */
fun ComposeTestRule.backToHomeOnce(status: String) {
    waitForText(status, substring = true)
    waitForIdle()
    pressInForm("Back to home")
    waitUntil(TIMEOUT_MILLIS) { onAllNodesWithText("Back to home").fetchSemanticsNodes().isEmpty() }
}

/** Presses "New sale" on the home screen: a floating button on a phone, not part of the scrolling content. */
fun ComposeTestRule.pressNewSale() {
    waitForText("New sale").performClick()
}

/** Types [pin] on the on-screen PIN pad, one key after the other; the pad may be under the fold. */
fun ComposeTestRule.enterPin(pin: String) {
    pin.forEach { digit ->
        onNode(hasText(digit.toString()) and hasClickAction()).performScrollTo().performClick()
    }
}

/** Scrolls to the key of the PIN pad that [description] describes, then presses it. */
fun ComposeTestRule.pressKey(description: String) {
    onNodeWithContentDescription(description).performScrollTo().performClick()
}
