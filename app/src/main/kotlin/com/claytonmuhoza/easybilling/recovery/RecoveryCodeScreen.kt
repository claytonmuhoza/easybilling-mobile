package com.claytonmuhoza.easybilling.recovery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/**
 * Shows a recovery code, once, and asks the user to confirm it was written down.
 *
 * @param code the recovery code, formatted in groups.
 * @param onAcknowledge called when the user confirms the code was written down.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun RecoveryCodeScreen(code: String, onAcknowledge: () -> Unit, modifier: Modifier = Modifier) {
    FormScreen(title = stringResource(R.string.recovery_code_title), modifier = modifier) {
        Text(
            text = stringResource(R.string.recovery_code_explanation),
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    modifier = Modifier.padding(Spacing.large),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_code_acknowledge))
        }
    }
}
