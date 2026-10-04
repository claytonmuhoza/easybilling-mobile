package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.R
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers

/** Diameter of a dot of [PinDots]. */
private val DotSize = 14.dp

/** Width of the outline of a dot that stands for a digit not typed yet. */
private val DotOutline = 2.dp

/** Size of a key of the [PinPad]: wide enough for a thumb, low enough for a small phone. */
private val KeyWidth = 72.dp
private val KeyHeight = 52.dp

/** Gap between two keys of a row of the [PinPad]. */
private val KeyGap = 20.dp

/** Room kept for the message of a [PinEntry], so the pad does not move when a message appears. */
private val MessageMinHeight = 40.dp

/** Opacity of what a disabled key shows. */
private const val DISABLED_ALPHA = 0.38f

/** The digits of the first three rows of the [PinPad]. */
private val DigitRows = listOf("123", "456", "789")

/**
 * The digits typed so far, one dot each: the digits themselves are never shown. The screen readers
 * are told how many digits are typed.
 *
 * @param count how many digits are typed.
 * @param modifier modifier applied to the dots.
 * @param slots how many dots are drawn at least; the ones not typed yet are outlined.
 * @param isError whether the dots take the colour of an error.
 */
@Composable
fun PinDots(count: Int, modifier: Modifier = Modifier, slots: Int = 0, isError: Boolean = false) {
    val description = pluralStringResource(R.plurals.ds_pin_digits_entered, count, count)
    val typed = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val missing = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        repeat(maxOf(count, slots)) { index ->
            val dot = if (index < count) {
                Modifier.background(typed, CircleShape)
            } else {
                Modifier.border(DotOutline, missing, CircleShape)
            }
            Box(Modifier.size(DotSize).then(dot))
        }
    }
}

/**
 * Numeric pad drawn on the screen, used instead of the keyboard of the system to type a PIN:
 * the digits, a key that deletes and a key that submits.
 *
 * @param onDigit called with the digit of the key pressed.
 * @param onBackspace called when the user asks to delete the last digit.
 * @param onSubmit called when the user submits what is typed.
 * @param submitDescription what the submit key does, read by the screen readers.
 * @param modifier modifier applied to the pad.
 * @param enabled whether the keys can be pressed.
 */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        DigitRows.forEach { digits ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                digits.forEach { digit -> DigitKey(digit, enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            PadKey(onClick = onBackspace, enabled = enabled, container = Color.Transparent) {
                AppIconImage(AppIcon.BACKSPACE, stringResource(R.string.ds_pin_backspace))
            }
            DigitKey('0', enabled) { onDigit('0') }
            PadKey(
                onClick = onSubmit,
                enabled = enabled,
                container = if (enabled) MaterialTheme.colorScheme.primary else KeyColor,
                content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            ) {
                AppIconImage(AppIcon.ARROW_FORWARD, submitDescription)
            }
        }
    }
}

/**
 * Everything needed to type a PIN without the keyboard of the system: the dots, a message under
 * them and the pad. The caller keeps the PIN; this component only adds and removes digits.
 *
 * @param pin the digits typed so far.
 * @param onPinChange called with the digits after a key was pressed.
 * @param maxLength number of digits after which the digit keys have no effect.
 * @param onSubmit called when the user submits the PIN.
 * @param submitDescription what the submit key does, read by the screen readers.
 * @param modifier modifier applied to the component.
 * @param slots how many dots are drawn at least.
 * @param message hint or error shown under the dots, or null.
 * @param isError whether [message] is an error.
 * @param enabled whether the keys can be pressed.
 */
@Composable
fun PinEntry(
    pin: String,
    onPinChange: (String) -> Unit,
    maxLength: Int,
    onSubmit: () -> Unit,
    submitDescription: String,
    modifier: Modifier = Modifier,
    slots: Int = 0,
    message: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        val messageColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        PinDots(count = pin.length, slots = slots, isError = isError)
        Box(modifier = Modifier.heightIn(min = MessageMinHeight), contentAlignment = Alignment.Center) {
            if (message != null) {
                Text(
                    text = message,
                    // An error is announced when it appears: nothing else tells a blind user.
                    modifier = if (isError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    color = messageColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        PinPad(
            onDigit = { digit -> if (pin.length < maxLength) onPinChange(pin + digit) },
            onBackspace = { if (pin.isNotEmpty()) onPinChange(pin.dropLast(1)) },
            onSubmit = onSubmit,
            submitDescription = submitDescription,
            enabled = enabled,
        )
    }
}

/** Colour of a digit key. */
private val KeyColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    PadKey(onClick = onClick, enabled = enabled, container = KeyColor) {
        Text(text = digit.toString(), style = MaterialTheme.typography.headlineSmall.tabularNumbers())
    }
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    enabled: Boolean,
    container: Color,
    content: Color = MaterialTheme.colorScheme.onSurface,
    label: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(KeyWidth, KeyHeight).semantics { role = Role.Button },
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
    ) {
        Box(contentAlignment = Alignment.Center) { label() }
    }
}
