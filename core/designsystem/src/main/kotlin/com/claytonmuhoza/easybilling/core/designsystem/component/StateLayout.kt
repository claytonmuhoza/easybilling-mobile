package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/** Size of the icon or progress indicator shown above the texts of a state. */
internal val StateIndicatorSize = 48.dp

/** Keeps the texts readable on a tablet instead of stretching across the whole screen. */
private val StateMaxWidth = 480.dp

/**
 * An action offered by a state component, shown as a button.
 *
 * @property label text of the button.
 * @property onClick called when the user presses the button.
 */
data class StateAction(val label: String, val onClick: () -> Unit)

/**
 * Layout shared by the loading, empty and error states: an indicator, then an optional title,
 * message and action, centred in the available space. Scrolls when the font is enlarged.
 *
 * @param title heading of the state, or null for none.
 * @param message explanation shown under the title, or null for none.
 * @param modifier modifier applied to the whole state.
 * @param action button shown under the texts, or null for none.
 * @param indicator the icon or progress indicator shown first.
 */
@Composable
internal fun StateLayout(
    title: String?,
    message: String?,
    modifier: Modifier = Modifier,
    action: StateAction? = null,
    indicator: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = StateMaxWidth)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            indicator()
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
            if (message != null) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Button(onClick = action.onClick) {
                    Text(action.label)
                }
            }
        }
    }
}
