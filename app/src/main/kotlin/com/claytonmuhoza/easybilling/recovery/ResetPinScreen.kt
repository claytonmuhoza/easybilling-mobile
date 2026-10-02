package com.claytonmuhoza.easybilling.recovery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormScreen
import com.claytonmuhoza.easybilling.identity.NewPinFields

/**
 * New PIN screen wired to its view model.
 *
 * @param onClose called to leave the screen, after a reset or when the user goes back.
 * @param viewModel holder of the screen state.
 */
@Composable
fun ResetPinRoute(onClose: () -> Unit, viewModel: ResetPinViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is ResetPinUiState.Form -> ResetPinScreen(
            state = current,
            onRecoveryCodeChange = viewModel::onRecoveryCodeChange,
            onPinChange = viewModel::onPinChange,
            onConfirmationChange = viewModel::onConfirmationChange,
            onSubmit = viewModel::submit,
            onBack = onClose,
        )
        is ResetPinUiState.Done -> RecoveryCodeScreen(code = current.newRecoveryCode, onAcknowledge = onClose)
    }
}

/**
 * Form to choose a new PIN with the recovery code.
 *
 * @param state the form state.
 * @param onRecoveryCodeChange called when the recovery code changes.
 * @param onPinChange called when the new PIN changes.
 * @param onConfirmationChange called when the PIN confirmation changes.
 * @param onSubmit called when the user asks to change the PIN.
 * @param onBack called when the user goes back to the login screen.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ResetPinScreen(
    state: ResetPinUiState.Form,
    onRecoveryCodeChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.submitting
    FormScreen(title = stringResource(R.string.reset_title), modifier = modifier, onBack = onBack) {
        Text(text = stringResource(R.string.reset_instruction), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.recoveryCode,
            onValueChange = onRecoveryCodeChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.field_recovery_code)) },
            supportingText = state.codeError?.let { error -> { Text(error.message()) } },
            isError = state.codeError != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
        )
        NewPinFields(
            pinLabel = stringResource(R.string.field_new_pin),
            pin = state.pin,
            onPinChange = onPinChange,
            confirmation = state.confirmation,
            onConfirmationChange = onConfirmationChange,
            problem = state.pinError,
            enabled = enabled,
            onDone = onSubmit,
        )
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
            Text(stringResource(R.string.reset_submit))
        }
    }
}

@Composable
private fun RecoveryCodeError.message(): String = when (this) {
    is RecoveryCodeError.Wrong ->
        pluralStringResource(R.plurals.reset_wrong_code, remainingAttempts, remainingAttempts)
    RecoveryCodeError.Locked -> stringResource(R.string.reset_locked)
}
