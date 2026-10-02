package com.claytonmuhoza.easybilling.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormScreen
import com.claytonmuhoza.easybilling.identity.NewPinFields
import com.claytonmuhoza.easybilling.recovery.RecoveryCodeScreen

/**
 * First launch assistant wired to its view model.
 *
 * @param onFinished called when the administrator exists and the assistant is over.
 * @param viewModel holder of the assistant state.
 */
@Composable
fun SetupRoute(onFinished: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is SetupUiState.Form -> SetupScreen(
            state = current,
            onNameChange = viewModel::onNameChange,
            onPinChange = viewModel::onPinChange,
            onConfirmationChange = viewModel::onConfirmationChange,
            onSubmit = viewModel::submit,
        )
        is SetupUiState.Created -> RecoveryCodeScreen(
            code = current.recoveryCode,
            onAcknowledge = { viewModel.acknowledgeRecoveryCode(onFinished) },
        )
        SetupUiState.AlreadySetUp -> LaunchedEffect(Unit) { onFinished() }
    }
}

/**
 * Form of the first launch assistant: name and PIN of the administrator.
 *
 * @param state the form state.
 * @param onNameChange called when the name changes.
 * @param onPinChange called when the PIN changes.
 * @param onConfirmationChange called when the PIN confirmation changes.
 * @param onSubmit called when the user asks to create the account.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SetupScreen(
    state: SetupUiState.Form,
    onNameChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.submitting
    FormScreen(title = stringResource(R.string.setup_title), modifier = modifier) {
        Text(text = stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(stringResource(R.string.field_name)) },
            supportingText = state.nameError?.let { error -> { Text(error.message()) } },
            isError = state.nameError != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
        )
        NewPinFields(
            pinLabel = stringResource(R.string.field_pin),
            pin = state.pin,
            onPinChange = onPinChange,
            confirmation = state.confirmation,
            onConfirmationChange = onConfirmationChange,
            problem = state.pinError,
            enabled = enabled,
            onDone = onSubmit,
        )
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
            Text(stringResource(R.string.setup_submit))
        }
    }
}

@Composable
private fun NameError.message(): String = stringResource(
    when (this) {
        NameError.BLANK -> R.string.error_name_blank
        NameError.TOO_LONG -> R.string.error_name_too_long
    },
)
