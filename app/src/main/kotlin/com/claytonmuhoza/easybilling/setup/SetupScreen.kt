package com.claytonmuhoza.easybilling.setup

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.BrandMark
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.domain.identity.UserNameProblem
import com.claytonmuhoza.easybilling.identity.PinStep
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
            actions = SetupActions(
                onNameChange = viewModel::onNameChange,
                onPinChange = viewModel::onPinChange,
                onConfirmationChange = viewModel::onConfirmationChange,
                onNext = viewModel::next,
                onBack = { viewModel.back() },
            ),
        )
        is SetupUiState.Created -> RecoveryCodeScreen(
            code = current.recoveryCode,
            onAcknowledge = { viewModel.acknowledgeRecoveryCode(onFinished) },
        )
        SetupUiState.AlreadySetUp -> LaunchedEffect(Unit) { onFinished() }
    }
}

/**
 * First launch assistant, one step at a time: the name of the administrator, the PIN, then the
 * PIN a second time. The PIN is typed on the PIN pad.
 *
 * @param state the form state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SetupScreen(state: SetupUiState.Form, actions: SetupActions, modifier: Modifier = Modifier) {
    val enabled = !state.submitting
    val firstStep = state.step == SetupStep.NAME
    BackHandler(enabled = !firstStep, onBack = actions.onBack)
    EntryScreen(
        modifier = modifier,
        onBack = actions.onBack.takeUnless { firstStep },
        header = {
            BrandMark()
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (firstStep) {
                Text(
                    text = stringResource(R.string.setup_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
    ) {
        when (state.step) {
            SetupStep.NAME -> NameStep(state, actions, enabled)
            SetupStep.PIN -> PinStep(
                title = stringResource(R.string.setup_pin_title),
                pin = state.pin,
                onPinChange = actions.onPinChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.action_continue),
                hint = stringResource(R.string.pin_rule_hint),
                problem = state.pinError,
                enabled = enabled,
            )
            SetupStep.CONFIRMATION -> PinStep(
                title = stringResource(R.string.field_pin_confirmation),
                pin = state.confirmation,
                onPinChange = actions.onConfirmationChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.setup_submit),
                hint = null,
                problem = state.pinError,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ColumnScope.NameStep(state: SetupUiState.Form, actions: SetupActions, enabled: Boolean) {
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onNameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.field_name)) },
        supportingText = state.nameError?.let { error -> { Text(error.message()) } },
        isError = state.nameError != null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { actions.onNext() }),
        singleLine = true,
    )
    Button(onClick = actions.onNext, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun UserNameProblem.message(): String = stringResource(
    when (this) {
        UserNameProblem.BLANK -> R.string.error_name_blank
        UserNameProblem.TOO_LONG -> R.string.error_name_too_long
    },
)
