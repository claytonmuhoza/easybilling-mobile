package com.claytonmuhoza.easybilling.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.BrandMark
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.identity.PinStep

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
            actions = ResetPinActions(
                onRecoveryCodeChange = viewModel::onRecoveryCodeChange,
                onPinChange = viewModel::onPinChange,
                onConfirmationChange = viewModel::onConfirmationChange,
                onNext = viewModel::next,
                onBack = { if (!viewModel.back()) onClose() },
            ),
        )
        is ResetPinUiState.Done -> RecoveryCodeScreen(code = current.newRecoveryCode, onAcknowledge = onClose)
    }
}

/**
 * Choice of a new PIN with the recovery code, one step at a time: the code, the new PIN, then
 * the new PIN a second time. The PIN is typed on the PIN pad.
 *
 * @param state the form state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ResetPinScreen(state: ResetPinUiState.Form, actions: ResetPinActions, modifier: Modifier = Modifier) {
    val enabled = !state.submitting
    val firstStep = state.step == ResetPinStep.CODE
    // On the first step the system back button leaves the screen by itself.
    BackHandler(enabled = !firstStep, onBack = actions.onBack)
    EntryScreen(
        modifier = modifier,
        onBack = actions.onBack,
        header = {
            BrandMark()
            Text(
                text = stringResource(R.string.reset_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (firstStep) {
                Text(
                    text = stringResource(R.string.reset_instruction),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
    ) {
        when (state.step) {
            ResetPinStep.CODE -> CodeStep(state, actions, enabled)
            ResetPinStep.PIN -> PinStep(
                title = stringResource(R.string.field_new_pin),
                pin = state.pin,
                onPinChange = actions.onPinChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.action_continue),
                hint = stringResource(R.string.pin_rule_hint),
                problem = state.pinError,
                enabled = enabled,
            )
            ResetPinStep.CONFIRMATION -> PinStep(
                title = stringResource(R.string.field_pin_confirmation),
                pin = state.confirmation,
                onPinChange = actions.onConfirmationChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.reset_submit),
                hint = null,
                problem = state.pinError,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ColumnScope.CodeStep(state: ResetPinUiState.Form, actions: ResetPinActions, enabled: Boolean) {
    OutlinedTextField(
        value = state.recoveryCode,
        onValueChange = actions.onRecoveryCodeChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.field_recovery_code)) },
        supportingText = state.codeError?.let { error -> { Text(error.message()) } },
        isError = state.codeError != null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { actions.onNext() }),
        singleLine = true,
    )
    Button(
        onClick = actions.onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && state.recoveryCode.isNotBlank(),
    ) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun RecoveryCodeError.message(): String = when (this) {
    is RecoveryCodeError.Wrong ->
        pluralStringResource(R.plurals.reset_wrong_code, remainingAttempts, remainingAttempts)
    RecoveryCodeError.Locked -> stringResource(R.string.reset_locked)
}
