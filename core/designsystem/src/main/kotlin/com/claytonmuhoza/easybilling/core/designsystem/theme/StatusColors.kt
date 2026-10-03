package com.claytonmuhoza.easybilling.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * What a status says, whatever it is the status of: an invoice, a TIN, a connection. A status is
 * always shown with its colour, its icon and its words, never with the colour alone.
 */
enum class StatusTone {
    /** Done and acknowledged: registered, verified, connected. */
    SUCCESS,

    /** Waiting for something: not sent yet, offline. */
    PENDING,

    /** Refused or failed: something must be done. */
    ERROR,

    /** No longer active: cancelled, archived. */
    NEUTRAL,
}

/**
 * The colours of one status.
 *
 * @property container colour of the background.
 * @property content colour of the icon and of the text on [container].
 */
data class StatusPalette(val container: Color, val content: Color)

/**
 * The colours of the statuses, which the Material colour scheme has no role for.
 *
 * @property success colours of [StatusTone.SUCCESS].
 * @property pending colours of [StatusTone.PENDING].
 */
data class StatusColors(val success: StatusPalette, val pending: StatusPalette)

internal val LightStatusColors = StatusColors(
    success = StatusPalette(container = Color(0xFFCDEFD6), content = Color(0xFF0B5D2A)),
    pending = StatusPalette(container = Color(0xFFFFDDB3), content = Color(0xFF5C3A00)),
)

// The dark colours swap the two tones of the light ones, as Material does for its containers.
internal val DarkStatusColors = StatusColors(
    success = StatusPalette(container = Color(0xFF0B5D2A), content = Color(0xFFCDEFD6)),
    pending = StatusPalette(container = Color(0xFF5C3A00), content = Color(0xFFFFDDB3)),
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * @return the colours of this status in the current theme. The error and the neutral statuses use
 * the roles of the Material colour scheme.
 */
@Composable
@ReadOnlyComposable
fun StatusTone.palette(): StatusPalette = when (this) {
    StatusTone.SUCCESS -> LocalStatusColors.current.success
    StatusTone.PENDING -> LocalStatusColors.current.pending
    StatusTone.ERROR -> StatusPalette(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
    StatusTone.NEUTRAL -> StatusPalette(
        container = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
