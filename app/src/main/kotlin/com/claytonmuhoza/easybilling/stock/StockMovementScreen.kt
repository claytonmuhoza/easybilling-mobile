package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.stock.MovementProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalDialog

/** The broken rules shown under the description. */
private val DESCRIPTION_PROBLEMS = setOf(MovementProblem.DESCRIPTION_REQUIRED, MovementProblem.DESCRIPTION_TOO_LONG)

/** Height of a type row: a touch target. */
private val TypeRowMinHeight = 48.dp

/**
 * Entry of a stock movement wired to its view model.
 *
 * @param onClose called to leave the screen: the user went back, or the movement is recorded.
 * @param viewModel holder of the entry state.
 */
@Composable
fun StockMovementRoute(onClose: () -> Unit, viewModel: StockMovementViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == StockMovementUiState.Done) LaunchedEffect(Unit) { onClose() }
    StockMovementScreen(
        state = state,
        actions = StockMovementActions(
            onBack = onClose,
            onFormChange = viewModel::onFormChange,
            onSubmit = viewModel::submit,
            onRetry = viewModel::load,
        ),
        approvalActions = ApprovalActions(
            onChoose = viewModel::choose,
            onPinChange = viewModel::onPinChange,
            onSubmit = viewModel::approve,
            onCancel = viewModel::dismissApproval,
        ),
    )
}

/**
 * What the user can do on the entry of a stock movement.
 *
 * @property onBack called when the user goes back.
 * @property onFormChange called with what the user changed of the movement being typed.
 * @property onSubmit called when the user records the movement.
 * @property onRetry called when the user asks to read the item again.
 */
class StockMovementActions(
    val onBack: () -> Unit = {},
    val onFormChange: (StockMovementUiState.Form.() -> StockMovementUiState.Form) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The entry of a stock movement (UC-F01, F02, F05, F07): its type, its quantity, the unit purchase
 * price of an entry and its description, required for an exit. A user who is not a manager is
 * asked for the PIN of one for an exit that is not a transfer.
 *
 * @param state the entry state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param approvalActions what the user can do on the approval of a manager.
 */
@Composable
fun StockMovementScreen(
    state: StockMovementUiState,
    actions: StockMovementActions,
    modifier: Modifier = Modifier,
    approvalActions: ApprovalActions = ApprovalActions(),
) {
    val exit = (state as? StockMovementUiState.Form)?.exit == true
    TitledScreen(
        title = stringResource(if (exit) R.string.stock_new_exit else R.string.stock_new_entry),
        modifier = modifier,
        onBack = actions.onBack,
    ) {
        when (state) {
            StockMovementUiState.Loading, StockMovementUiState.Done -> LoadingState()
            StockMovementUiState.Failed -> ErrorState(
                message = stringResource(R.string.stock_item_load_error),
                onRetry = actions.onRetry,
            )
            is StockMovementUiState.Unavailable -> ErrorState(message = state.problem.message())
            is StockMovementUiState.Form -> {
                val type = state.type
                if (state.approval != null && type != null) {
                    ApprovalDialog(
                        message = stringResource(
                            R.string.stock_exit_approval,
                            state.quantity.trim(),
                            state.item.details.designation,
                            type.label(),
                        ),
                        state = state.approval,
                        actions = approvalActions,
                        busy = state.busy,
                    )
                }
                MovementForm(state, actions)
            }
        }
    }
}

@Composable
private fun MovementForm(state: StockMovementUiState.Form, actions: StockMovementActions) {
    FormColumn {
        Text(
            text = state.item.details.designation,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.stock_in_stock, quantityText(state.available, state.item.details.unit)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TypeChoice(state) { type ->
            actions.onFormChange { copy(type = type, problems = problems - MovementProblem.TYPE_MISSING) }
        }
        NumberField(
            value = state.quantity,
            onValueChange = { quantity ->
                actions.onFormChange {
                    copy(quantity = quantity, problems = problems - MovementProblem.QUANTITY_INVALID)
                }
            },
            label = stringResource(R.string.stock_field_quantity),
            error = state.problemText(MovementProblem.QUANTITY_INVALID),
        )
        if (!state.exit) {
            NumberField(
                value = state.unitCost,
                onValueChange = { cost ->
                    actions.onFormChange { copy(unitCost = cost, problems = problems - MovementProblem.COST_INVALID) }
                },
                label = stringResource(R.string.stock_field_cost),
                error = state.problemText(MovementProblem.COST_INVALID),
            )
        }
        DescriptionField(state) { description ->
            actions.onFormChange { copy(description = description, problems = problems - DESCRIPTION_PROBLEMS) }
        }
        state.insufficient?.let { available ->
            val quantity = quantityText(available, state.item.details.unit)
            StatusBanner(StatusTone.ERROR, stringResource(R.string.stock_insufficient, quantity))
        }
        if (state.failed) StatusBanner(StatusTone.ERROR, stringResource(R.string.stock_save_error))
        Button(onClick = actions.onSubmit, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(stringResource(if (state.exit) R.string.stock_record_exit else R.string.stock_record_entry))
        }
    }
}

/** The types offered, one per row: there are too many for a row of buttons on a phone. */
@Composable
private fun TypeChoice(state: StockMovementUiState.Form, onSelect: (MovementType) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        Text(text = stringResource(R.string.stock_field_type), style = MaterialTheme.typography.bodyLarge)
        state.types.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TypeRowMinHeight)
                    .selectable(selected = type == state.type, role = Role.RadioButton, onClick = { onSelect(type) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == state.type, onClick = null)
                Text(text = type.label(), modifier = Modifier.padding(start = Spacing.medium))
            }
        }
        state.problemText(MovementProblem.TYPE_MISSING)?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.type in state.needsApproval) {
            Text(
                text = stringResource(R.string.stock_exit_validated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
    )
}

@Composable
private fun DescriptionField(state: StockMovementUiState.Form, onValueChange: (String) -> Unit) {
    val error = state.problemText(MovementProblem.DESCRIPTION_REQUIRED)
        ?: state.problemText(MovementProblem.DESCRIPTION_TOO_LONG)
    val hint = stringResource(
        if (state.exit) R.string.stock_description_required else R.string.stock_description_optional,
    )
    OutlinedTextField(
        value = state.description,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.stock_field_description)) },
        supportingText = { Text(error ?: hint) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/** @return the message of [problem] when the last attempt broke it, or null. */
@Composable
private fun StockMovementUiState.Form.problemText(problem: MovementProblem): String? =
    problem.takeIf { it in problems }?.message()
