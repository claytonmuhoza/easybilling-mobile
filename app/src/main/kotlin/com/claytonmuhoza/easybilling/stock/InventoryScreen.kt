package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.stock.CloseInventory
import com.claytonmuhoza.easybilling.domain.stock.CountProblem
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalDialog
import com.claytonmuhoza.easybilling.ui.amountText
import java.math.BigDecimal

/**
 * Inventory wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the inventory state.
 */
@Composable
fun InventoryRoute(onBack: () -> Unit, viewModel: InventoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InventoryScreen(
        state = state,
        actions = InventoryActions(
            onBack = onBack,
            onQueryChange = viewModel::onQueryChange,
            onCount = viewModel::onCount,
            onNoteChange = viewModel::onNoteChange,
            onClose = viewModel::close,
            onDiscard = viewModel::discard,
            onRetry = viewModel::load,
        ),
        approvalActions = ApprovalActions(
            onChoose = { id -> viewModel.onApprovalChange { copy(chosen = id, pin = "", error = null) } },
            onPinChange = { pin -> viewModel.onApprovalChange { copy(pin = pin, error = null) } },
            onSubmit = viewModel::approve,
            onCancel = { viewModel.onApprovalChange { null } },
        ),
    )
}

/**
 * What the user can do on the inventory.
 *
 * @property onBack called when the user goes back.
 * @property onQueryChange called with the new content of the search field.
 * @property onCount called with the identifier of an item, its quantity and its unit purchase
 * price as typed.
 * @property onNoteChange called when the note changes.
 * @property onClose called when the user closes the inventory.
 * @property onDiscard called when the user gives the inventory up.
 * @property onRetry called when the user asks to read the inventory again.
 */
class InventoryActions(
    val onBack: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onCount: (Long, String, String) -> Unit = { _, _, _ -> },
    val onNoteChange: (String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The inventory (UC-F08): what is counted of each item kept in stock, the gap with its stock, and
 * the closing that adjusts the stock. What is counted is kept until then.
 *
 * @param state the inventory state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param approvalActions what the user can do on the approval of a manager.
 */
@Composable
fun InventoryScreen(
    state: InventoryUiState,
    actions: InventoryActions,
    modifier: Modifier = Modifier,
    approvalActions: ApprovalActions = ApprovalActions(),
) {
    TitledScreen(title = stringResource(R.string.inventory_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            InventoryUiState.Loading -> LoadingState()
            InventoryUiState.Failed -> ErrorState(
                message = stringResource(R.string.inventory_load_error),
                onRetry = actions.onRetry,
            )
            is InventoryUiState.Ready -> {
                state.approval?.let { approval ->
                    ApprovalDialog(
                        message = pluralStringResource(R.plurals.inventory_approval, state.gaps, state.gaps),
                        state = approval,
                        actions = approvalActions,
                        busy = state.busy,
                    )
                }
                InventoryContent(state, actions)
            }
        }
    }
}

@Composable
private fun InventoryContent(state: InventoryUiState.Ready, actions: InventoryActions) {
    FormColumn {
        Text(text = stringResource(R.string.inventory_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.closedWith?.let { adjustments ->
            StatusBanner(
                StatusTone.SUCCESS,
                pluralStringResource(R.plurals.inventory_closed, adjustments, adjustments),
            )
        }
        SearchField(state.query, actions.onQueryChange, stringResource(R.string.items_search))
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(
                    if (state.query.isBlank()) R.string.stock_empty_title else R.string.stock_no_match,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.rows.forEach { row -> InventoryRowCard(row, actions.onCount) }
        Closing(state, actions)
    }
}

/** An item: its stock, what is counted of it, the gap and, for an excess, its unit purchase price. */
@Composable
private fun InventoryRowCard(row: InventoryRow, onCount: (Long, String, String) -> Unit) {
    val item = row.line.item
    val gap = row.line.gap
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = item.details.designation,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.inventory_expected,
                    item.code,
                    quantityText(row.line.expected, item.details.unit),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val counted = stringResource(R.string.inventory_counted_of, item.details.designation)
            NumberField(
                value = row.counted,
                onValueChange = { onCount(item.id.value, it, row.cost) },
                label = stringResource(R.string.inventory_counted),
                error = stringResource(R.string.stock_problem_quantity)
                    .takeIf { CountProblem.QUANTITY_INVALID in row.problems },
                modifier = Modifier.semantics { contentDescription = counted },
            )
            if (gap != null) GapText(gap, item.details.unit)
            if (gap != null && gap.signum() > 0) {
                val lastCost = row.line.lastCost
                NumberField(
                    value = row.cost,
                    onValueChange = { onCount(item.id.value, row.counted, it) },
                    label = stringResource(R.string.stock_field_cost),
                    error = when {
                        CountProblem.COST_INVALID in row.problems -> stringResource(R.string.stock_problem_cost)
                        row.line.missingCost -> stringResource(R.string.inventory_cost_missing)
                        else -> null
                    },
                    hint = lastCost?.let { stringResource(R.string.inventory_last_cost, amountText(it)) },
                )
            }
        }
    }
}

@Composable
private fun GapText(gap: BigDecimal, unit: String) {
    val text = when (gap.signum()) {
        0 -> stringResource(R.string.inventory_no_gap)
        1 -> stringResource(
            R.string.inventory_gap,
            stringResource(R.string.stock_movement_entry, quantityText(gap, unit)),
        )
        else -> stringResource(
            R.string.inventory_gap,
            stringResource(R.string.stock_movement_exit, quantityText(gap.negate(), unit)),
        )
    }
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        color = if (gap.signum() == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

/** The note, why the inventory was not closed, and the buttons that close it or give it up. */
@Composable
private fun Closing(state: InventoryUiState.Ready, actions: InventoryActions) {
    CountedTextField(
        value = state.note,
        onValueChange = actions.onNoteChange,
        label = stringResource(R.string.inventory_note),
        maxLength = CloseInventory.MAX_NOTE_LENGTH,
        errorMessage = stringResource(R.string.inventory_note_too_long)
            .takeIf { state.refusal == InventoryRefusal.NOTE_TOO_LONG },
        hint = stringResource(R.string.stock_description_optional),
        capitalization = KeyboardCapitalization.Sentences,
    )
    state.refusal?.message()?.let { StatusBanner(StatusTone.ERROR, it) }
    if (state.needsApproval) {
        Text(
            text = stringResource(R.string.inventory_validated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = actions.onClose, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(R.string.inventory_close))
    }
    OutlinedButton(
        onClick = actions.onDiscard,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy && state.countedRows > 0,
    ) {
        Text(stringResource(R.string.inventory_discard))
    }
}

/** @return the refusal in words; the one of the note is said under its field. */
@Composable
private fun InventoryRefusal.message(): String? = when (this) {
    InventoryRefusal.NOTHING_COUNTED -> stringResource(R.string.inventory_nothing_counted)
    InventoryRefusal.MISSING_COST -> stringResource(R.string.inventory_cost_missing_any)
    InventoryRefusal.FAILED -> stringResource(R.string.stock_save_error)
    InventoryRefusal.NOTE_TOO_LONG -> null
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val supporting = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}
