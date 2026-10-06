package com.claytonmuhoza.easybilling.ebms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.allowed
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Log of the exchanges wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenExchange called with the identifier of the exchange the user picks.
 * @param viewModel holder of the log state.
 */
@Composable
fun ExchangeLogRoute(
    onBack: () -> Unit,
    onOpenExchange: (Long) -> Unit,
    viewModel: ExchangeLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExchangeLogScreen(
        state = state,
        actions = ExchangeLogActions(
            onBack = onBack,
            onOpenExchange = onOpenExchange,
            onRetry = viewModel::load,
            onOpenRetention = viewModel::openRetention,
            onRetentionChange = viewModel::onRetentionChange,
            onConfirmRetention = viewModel::confirmRetention,
            onDismissRetention = viewModel::dismissRetention,
        ),
    )
}

/**
 * What the user can do on the log of the exchanges.
 *
 * @property onBack called when the user goes back.
 * @property onOpenExchange called with the identifier of the exchange the user picks.
 * @property onRetry called when the user asks to read the log again.
 * @property onOpenRetention called when the user asks to change the retention.
 * @property onRetentionChange called when the number of days changes.
 * @property onConfirmRetention called when the user confirms the number of days.
 * @property onDismissRetention called when the user cancels the change.
 */
class ExchangeLogActions(
    val onBack: () -> Unit = {},
    val onOpenExchange: (Long) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenRetention: () -> Unit = {},
    val onRetentionChange: (String) -> Unit = {},
    val onConfirmRetention: () -> Unit = {},
    val onDismissRetention: () -> Unit = {},
)

/**
 * The exchanges with the OBR, newest first, and how long they are kept (UC-D08).
 *
 * @param state the log state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ExchangeLogScreen(state: ExchangeLogUiState, actions: ExchangeLogActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.exchanges_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            ExchangeLogUiState.Loading -> LoadingState()
            ExchangeLogUiState.Failed -> ErrorState(
                message = stringResource(R.string.exchanges_load_error),
                onRetry = actions.onRetry,
            )
            is ExchangeLogUiState.Ready -> Column {
                Retention(state.retentionDays, state.retentionEditor, actions)
                if (state.exchanges.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.exchanges_empty_title),
                        message = stringResource(R.string.exchanges_empty_message),
                    )
                } else {
                    FormColumn {
                        state.exchanges.forEach { ExchangeCard(it, actions.onOpenExchange) }
                    }
                }
            }
        }
    }
}

/**
 * How long the exchanges are kept, and its editor when open. The editor stays in the screen
 * rather than in a dialog: a text field inside a dialog never settled on some phone sizes in the
 * tests.
 */
@Composable
private fun Retention(days: Int, editor: RetentionEditor?, actions: ExchangeLogActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.exchanges_retention, days, days),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (editor == null && allowed(Permission.CONFIGURE_BILLING)) {
                TextButton(onClick = actions.onOpenRetention) { Text(stringResource(R.string.exchanges_change)) }
            }
        }
        if (editor != null) {
            OutlinedTextField(
                value = editor.days,
                onValueChange = actions.onRetentionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.exchanges_retention_days)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (editor.invalid) {
                                R.string.exchanges_retention_error
                            } else {
                                R.string.exchanges_retention_hint
                            },
                        ),
                    )
                },
                isError = editor.invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions.onDismissRetention) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = actions.onConfirmRetention) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}

@Composable
private fun ExchangeCard(exchange: ObrExchange, onOpen: (Long) -> Unit) {
    TonalCard(onClick = { onOpen(exchange.id) }, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            headlineContent = { Text("${exchange.service} · ${exchange.statusText()}") },
            supportingContent = {
                Text(
                    "${dateTimeText(exchange.startedAt)} · ${exchange.environment.label()} · " +
                        stringResource(R.string.exchange_duration_value, exchange.durationMillis),
                )
            },
        )
    }
}

@Composable
private fun ObrExchange.statusText(): String =
    statusCode?.toString() ?: stringResource(R.string.exchanges_no_answer)


/**
 * One exchange wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the exchange state.
 */
@Composable
fun ExchangeRoute(onBack: () -> Unit, viewModel: ExchangeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExchangeScreen(state = state, onBack = onBack, onRetry = viewModel::load)
}

/**
 * One exchange with the OBR: when, where, what was sent and received, secrets masked.
 *
 * @param state the exchange state.
 * @param onBack called when the user goes back.
 * @param onRetry called when the user asks to read the exchange again.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ExchangeScreen(state: ExchangeUiState, onBack: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.exchange_title), modifier = modifier, onBack = onBack) {
        when (state) {
            ExchangeUiState.Loading -> LoadingState()
            ExchangeUiState.Failed -> ErrorState(
                message = stringResource(R.string.exchanges_load_error),
                onRetry = onRetry,
            )
            ExchangeUiState.NotFound -> ErrorState(message = stringResource(R.string.exchange_not_found))
            is ExchangeUiState.Ready -> FormColumn { ExchangeContent(state.exchange) }
        }
    }
}

@Composable
private fun ExchangeContent(exchange: ObrExchange) {
    LabeledText(stringResource(R.string.exchange_date), dateTimeText(exchange.startedAt))
    LabeledText(stringResource(R.string.exchange_environment), exchange.environment.label())
    LabeledText(stringResource(R.string.exchange_service), exchange.service)
    LabeledText(stringResource(R.string.exchange_status), exchange.statusText())
    LabeledText(
        stringResource(R.string.exchange_duration),
        stringResource(R.string.exchange_duration_value, exchange.durationMillis),
    )
    if (exchange.failure.isNotEmpty()) {
        LabeledText(stringResource(R.string.exchange_failure), exchange.failure)
    }
    Body(stringResource(R.string.exchange_request), exchange.requestBody)
    if (exchange.responseBody.isNotEmpty()) {
        Body(stringResource(R.string.exchange_response), exchange.responseBody)
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        FieldLabel(label)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Body(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        FieldLabel(label)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            SelectionContainer {
                Text(
                    text = body,
                    modifier = Modifier.padding(Spacing.medium),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
