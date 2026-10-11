package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.allowed
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Stock of an item wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onNewMovement called with the identifier of the item and whether the movement to type is
 * an exit.
 * @param viewModel holder of the stock state.
 */
@Composable
fun StockItemRoute(
    onBack: () -> Unit,
    onNewMovement: (Long, Boolean) -> Unit,
    viewModel: StockItemViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a movement may have been recorded on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    StockItemScreen(
        state = state,
        actions = StockItemActions(
            onBack = onBack,
            onNewMovement = onNewMovement,
            onRetry = viewModel::load,
            onThresholdChange = viewModel::onThresholdChange,
            onSaveThreshold = viewModel::saveThreshold,
        ),
    )
}

/**
 * What the user can do on the stock of an item.
 *
 * @property onBack called when the user goes back.
 * @property onNewMovement called with the identifier of the item and whether the movement to type
 * is an exit.
 * @property onRetry called when the user asks to read the stock again.
 * @property onThresholdChange called with the new content of the threshold field.
 * @property onSaveThreshold called when the user keeps the threshold typed.
 */
class StockItemActions(
    val onBack: () -> Unit = {},
    val onNewMovement: (Long, Boolean) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onThresholdChange: (String) -> Unit = {},
    val onSaveThreshold: () -> Unit = {},
)

/**
 * The stock of an item (UC-F10): its quantity, the way to an entry or an exit, and its movements
 * with where each stands with the OBR.
 *
 * @param state the stock state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun StockItemScreen(state: StockItemUiState, actions: StockItemActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.stock_item_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            StockItemUiState.Loading -> LoadingState()
            StockItemUiState.Failed -> ErrorState(
                message = stringResource(R.string.stock_item_load_error),
                onRetry = actions.onRetry,
            )
            is StockItemUiState.Unavailable -> ErrorState(message = state.problem.message())
            is StockItemUiState.Ready -> FormColumn {
                Summary(state)
                MovementButtons(state, actions)
                Threshold(state, actions)
                SectionHeader(stringResource(R.string.stock_history))
                if (state.movements.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stock_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.movements.forEach { MovementCard(it) }
                if (state.movements.size == StockItemViewModel.LIMIT) {
                    Text(
                        text = stringResource(R.string.stock_history_limit, StockItemViewModel.LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Summary(state: StockItemUiState.Ready) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = state.item.details.designation,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = state.item.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(
                    R.string.stock_in_stock,
                    quantityText(state.quantity, state.item.details.unit),
                ),
                style = MaterialTheme.typography.headlineSmall.tabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MovementButtons(state: StockItemUiState.Ready, actions: StockItemActions) {
    if (state.item.archived) {
        StatusBanner(StatusTone.NEUTRAL, StockProblem.ITEM_ARCHIVED.message())
        return
    }
    if (!allowed(Permission.RECORD_STOCK)) return
    val itemId = state.item.id.value
    Button(onClick = { actions.onNewMovement(itemId, false) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stock_new_entry))
    }
    OutlinedButton(
        onClick = { actions.onNewMovement(itemId, true) },
        modifier = Modifier.fillMaxWidth(),
        // Nothing can leave an empty stock.
        enabled = state.quantity.signum() > 0,
    ) {
        Text(stringResource(R.string.stock_new_exit))
    }
}

/** The reorder threshold: read by everyone, set by who may record a movement (UC-F10). */
@Composable
private fun Threshold(state: StockItemUiState.Ready, actions: StockItemActions) {
    val unit = state.item.details.unit
    SectionHeader(stringResource(R.string.stock_threshold_title))
    if (state.toReorder) StatusBanner(StatusTone.PENDING, stringResource(R.string.stock_to_reorder))
    if (!allowed(Permission.RECORD_STOCK) || state.item.archived) {
        val threshold = state.threshold
        Text(
            text = if (threshold == null) {
                stringResource(R.string.stock_threshold_none)
            } else {
                stringResource(R.string.stock_threshold_value, quantityText(threshold, unit))
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val error = stringResource(R.string.stock_threshold_invalid).takeIf { state.thresholdInvalid }
    OutlinedTextField(
        value = state.thresholdText,
        onValueChange = actions.onThresholdChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.stock_threshold_field)) },
        supportingText = { Text(error ?: stringResource(R.string.stock_threshold_help, unit)) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
    OutlinedButton(onClick = actions.onSaveThreshold, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stock_threshold_save))
    }
}

/** A movement: its type, what it does to the stock, its reference and where it stands with the OBR. */
@Composable
private fun MovementCard(row: MovementRow) {
    val content = row.movement.content
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = content.type.label(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = content.signedQuantityText(),
                    style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(
                    R.string.stock_movement_detail,
                    content.reference,
                    dateTimeText(content.occurredAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (content.description.isNotEmpty()) Text(content.description)
            StatusChip(row.status.tone(), row.status.label())
            (row.status as? MovementStatus.Rejected)?.let { rejected ->
                Text(
                    text = stringResource(R.string.transmission_obr_reason, rejected.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
