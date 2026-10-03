package com.claytonmuhoza.easybilling.ebms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.PinField
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialField
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.ui.message
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * EBMS connection screen wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenExchanges called when the user opens the log of the exchanges.
 * @param viewModel holder of the screen state.
 */
@Composable
fun EbmsConnectionRoute(
    onBack: () -> Unit,
    onOpenExchanges: () -> Unit,
    viewModel: EbmsConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EbmsConnectionScreen(
        state = state,
        actions = EbmsConnectionActions(
            onEnvironmentSelected = viewModel::onEnvironmentSelected,
            onServerUrlChange = viewModel::onServerUrlChange,
            onFieldChange = viewModel::onFieldChange,
            onSave = viewModel::save,
            onTest = viewModel::test,
            onPinChange = viewModel::onPinChange,
            onConfirmPin = viewModel::confirmPin,
            onDismissPin = viewModel::dismissPin,
            onRetry = viewModel::load,
            onBack = onBack,
            onOpenExchanges = onOpenExchanges,
        ),
    )
}

/**
 * What the user can do on the EBMS connection screen.
 *
 * @property onEnvironmentSelected called when an environment is picked.
 * @property onServerUrlChange called when the address changes.
 * @property onFieldChange called when a credential field changes.
 * @property onSave called when the user asks to save.
 * @property onTest called when the user asks to test the connection.
 * @property onPinChange called when the PIN of the production prompt changes.
 * @property onConfirmPin called when the user confirms the PIN.
 * @property onDismissPin called when the user cancels the PIN prompt.
 * @property onRetry called when the user asks to read the settings again.
 * @property onBack called when the user goes back.
 * @property onOpenExchanges called when the user opens the log of the exchanges.
 */
class EbmsConnectionActions(
    val onEnvironmentSelected: (EbmsEnvironment) -> Unit = {},
    val onServerUrlChange: (String) -> Unit = {},
    val onFieldChange: (EbmsCredentialField, String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onConfirmPin: () -> Unit = {},
    val onDismissPin: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenExchanges: () -> Unit = {},
)

/**
 * Settings of the connection to the EBMS and test of the connection (UC-A03 to UC-A05).
 *
 * @param state the screen state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun EbmsConnectionScreen(state: EbmsConnectionUiState, actions: EbmsConnectionActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.ebms_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.ebms_exchanges), onClick = actions.onOpenExchanges) },
    ) {
        when (state) {
            EbmsConnectionUiState.Loading -> LoadingState()
            EbmsConnectionUiState.LoadFailed -> ErrorState(
                message = stringResource(R.string.ebms_load_error),
                onRetry = actions.onRetry,
            )
            is EbmsConnectionUiState.Ready -> FormColumn { ConnectionContent(state, actions) }
        }
    }
}

@Composable
private fun ColumnScope.ConnectionContent(state: EbmsConnectionUiState.Ready, actions: EbmsConnectionActions) {
    ChoiceField(
        label = stringResource(R.string.ebms_section_environment),
        options = EbmsEnvironment.entries.map { it to it.label() },
        selected = state.activeEnvironment,
        onSelect = actions.onEnvironmentSelected,
    )
    Hint(stringResource(R.string.ebms_environment_hint))
    state.pinPrompt?.let { PinConfirmation(it, actions) }

    SectionHeader(stringResource(R.string.ebms_section_settings))
    OutlinedTextField(
        value = state.form.serverUrl,
        onValueChange = actions.onServerUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.ebms_field_server_url)) },
        supportingText = state.urlProblem?.let { problem -> { Text(problem.message()) } },
        isError = state.urlProblem != null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )
    CredentialField(EbmsCredentialField.USERNAME, state, actions)
    CredentialField(EbmsCredentialField.PASSWORD, state, actions)
    state.saveFeedback?.let { feedback ->
        Feedback(
            text = stringResource(
                if (feedback == SaveFeedback.SAVED) R.string.ebms_saved else R.string.ebms_save_error,
            ),
            isError = feedback == SaveFeedback.FAILED,
        )
    }
    Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) {
        Text(stringResource(R.string.ebms_save))
    }

    SectionHeader(stringResource(R.string.ebms_section_test))
    Hint(
        state.lastSuccess?.let { stringResource(R.string.ebms_last_success, dateTimeText(it)) }
            ?: stringResource(R.string.ebms_never_tested),
    )
    state.testOutcome?.let { Feedback(text = it.message(), isError = it !is LoginOutcome.Success) }
    OutlinedButton(onClick = actions.onTest, modifier = Modifier.fillMaxWidth(), enabled = !state.testing) {
        Text(stringResource(if (state.testing) R.string.ebms_testing else R.string.ebms_test))
    }
}

@Composable
private fun CredentialField(
    field: EbmsCredentialField,
    state: EbmsConnectionUiState.Ready,
    actions: EbmsConnectionActions,
) {
    val isPassword = field == EbmsCredentialField.PASSWORD
    CountedTextField(
        value = if (isPassword) state.form.password else state.form.username,
        onValueChange = { actions.onFieldChange(field, it) },
        label = field.label(),
        maxLength = field.maxLength,
        errorMessage = state.problems[field]?.message(),
        hint = when {
            !isPassword -> stringResource(R.string.ebms_field_system_id_hint)
            state.hasStoredPassword -> stringResource(R.string.ebms_password_kept)
            else -> null
        },
        keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
        capitalization = KeyboardCapitalization.None,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A result shown after an action, announced by screen readers. */
@Composable
private fun Feedback(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text = text, modifier = Modifier.padding(Spacing.medium), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The PIN asked before going to production, shown in the screen rather than in a dialog: a text
 * field inside a dialog never settled on some phone sizes in the tests.
 */
@Composable
private fun PinConfirmation(prompt: PinPrompt, actions: EbmsConnectionActions) {
    val enabled = !prompt.checking && !prompt.locked
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.ebms_production_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ebms_production_text), style = MaterialTheme.typography.bodyMedium)
            PinField(
                value = prompt.pin,
                onValueChange = actions.onPinChange,
                label = stringResource(R.string.field_pin),
                maxLength = Pin.MAX_LENGTH,
                errorMessage = when {
                    prompt.locked -> stringResource(R.string.reset_locked)
                    prompt.remainingAttempts != null -> pluralStringResource(
                        R.plurals.login_wrong_pin,
                        prompt.remainingAttempts,
                        prompt.remainingAttempts,
                    )
                    else -> null
                },
                enabled = enabled,
                imeAction = ImeAction.Done,
                onImeAction = actions.onConfirmPin,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions.onDismissPin) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = actions.onConfirmPin, enabled = enabled) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}
