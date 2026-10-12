package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.stock.ImportProblem
import com.claytonmuhoza.easybilling.ui.decimalText

/** The broken rules shown under the packaging. */
private val PACKAGING_PROBLEMS = setOf(ImportProblem.PACKAGING_MISSING, ImportProblem.PACKAGING_TOO_LONG)

/** The message of each broken rule: a problem without a message fails the first time it is shown. */
internal val IMPORT_PROBLEM_MESSAGES = mapOf(
    ImportProblem.ITEM_MISSING to R.string.import_problem_item,
    ImportProblem.QUANTITY_INVALID to R.string.stock_problem_quantity,
    ImportProblem.COST_INVALID to R.string.stock_problem_cost,
    ImportProblem.PER_PACKET_INVALID to R.string.import_problem_per_packet,
    ImportProblem.PACKAGING_MISSING to R.string.import_problem_packaging_missing,
    ImportProblem.PACKAGING_TOO_LONG to R.string.import_problem_packaging_long,
    ImportProblem.DESCRIPTION_TOO_LONG to R.string.stock_problem_description_too_long,
)

/**
 * Entry of imported goods wired to its view model.
 *
 * @param onClose called to leave the screen: the user went back, or the entry is recorded.
 * @param onNewItem called when the user wants to create the item the goods are.
 * @param viewModel holder of the entry state.
 */
@Composable
fun ImportEntryRoute(onClose: () -> Unit, onNewItem: () -> Unit, viewModel: ImportEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the item may have been created on another screen.
    LaunchedEffect(Unit) { viewModel.load() }
    if (state == ImportEntryUiState.Done) LaunchedEffect(Unit) { onClose() }

    ImportEntryScreen(
        state = state,
        actions = ImportEntryActions(
            onBack = onClose,
            onNewItem = onNewItem,
            onQueryChange = viewModel::onQueryChange,
            onFormChange = viewModel::onFormChange,
            onSubmit = viewModel::submit,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the entry of imported goods.
 *
 * @property onBack called when the user goes back.
 * @property onNewItem called when the user wants to create the item the goods are.
 * @property onQueryChange called with the new content of the search of the item.
 * @property onFormChange called with what the user changed of the entry being typed.
 * @property onSubmit called when the user records the entry.
 * @property onRetry called when the user asks to read the line again.
 */
class ImportEntryActions(
    val onBack: () -> Unit = {},
    val onNewItem: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onFormChange: (ImportEntryUiState.Form.() -> ImportEntryUiState.Form) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The entry into the stock of a line of a customs declaration (UC-F04): the item of the catalogue
 * the goods are, the quantity, the unit purchase price and the packaging.
 *
 * @param state the entry state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ImportEntryScreen(state: ImportEntryUiState, actions: ImportEntryActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.import_entry_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            ImportEntryUiState.Loading, ImportEntryUiState.Done -> LoadingState()
            ImportEntryUiState.Failed -> ErrorState(
                message = stringResource(R.string.imports_load_error),
                onRetry = actions.onRetry,
            )
            ImportEntryUiState.LineNotFound -> ErrorState(message = stringResource(R.string.import_line_gone))
            is ImportEntryUiState.Form -> EntryForm(state, actions)
        }
    }
}

@Composable
private fun EntryForm(state: ImportEntryUiState.Form, actions: ImportEntryActions) {
    FormColumn {
        LineSummary(state)
        ItemChoice(state, actions)
        TypedField(
            value = state.quantity,
            onValueChange = { quantity ->
                actions.onFormChange { copy(quantity = quantity, problems = problems - ImportProblem.QUANTITY_INVALID) }
            },
            label = state.item?.let { stringResource(R.string.import_field_quantity_in, it.details.unit) }
                ?: stringResource(R.string.stock_field_quantity),
            error = state.problemText(ImportProblem.QUANTITY_INVALID),
        )
        TypedField(
            value = state.unitCost,
            onValueChange = { cost ->
                actions.onFormChange { copy(unitCost = cost, problems = problems - ImportProblem.COST_INVALID) }
            },
            label = stringResource(R.string.stock_field_cost),
            error = state.problemText(ImportProblem.COST_INVALID),
        )
        TypedField(
            value = state.piecesPerPacket,
            onValueChange = { pieces ->
                actions.onFormChange {
                    copy(piecesPerPacket = pieces, problems = problems - ImportProblem.PER_PACKET_INVALID)
                }
            },
            label = stringResource(R.string.import_field_per_packet),
            error = state.problemText(ImportProblem.PER_PACKET_INVALID),
        )
        PacketFields(state, actions)
        state.exceeds?.let { StatusBanner(StatusTone.ERROR, stringResource(R.string.import_exceeds, decimalText(it))) }
        state.refused?.let { StatusBanner(StatusTone.ERROR, it.message()) }
        if (state.failed) StatusBanner(StatusTone.ERROR, stringResource(R.string.stock_save_error))
        Button(onClick = actions.onSubmit, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(stringResource(R.string.import_record))
        }
    }
}

/** The kind of packaging and the description of the entry. */
@Composable
private fun PacketFields(state: ImportEntryUiState.Form, actions: ImportEntryActions) {
    TypedField(
        value = state.packaging,
        onValueChange = { packaging ->
            actions.onFormChange { copy(packaging = packaging, problems = problems - PACKAGING_PROBLEMS) }
        },
        label = stringResource(R.string.import_field_packaging),
        error = state.problemText(ImportProblem.PACKAGING_MISSING)
            ?: state.problemText(ImportProblem.PACKAGING_TOO_LONG),
        hint = stringResource(R.string.import_packaging_hint),
        number = false,
    )
    TypedField(
        value = state.description,
        onValueChange = { description ->
            actions.onFormChange {
                copy(description = description, problems = problems - ImportProblem.DESCRIPTION_TOO_LONG)
            }
        },
        label = stringResource(R.string.stock_field_description),
        error = state.problemText(ImportProblem.DESCRIPTION_TOO_LONG),
        hint = stringResource(R.string.stock_description_optional),
        number = false,
    )
}

/** The line of the declaration the goods come from. */
@Composable
private fun LineSummary(state: ImportEntryUiState.Form) {
    val line = state.line.line
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = stringResource(R.string.import_line_of, state.reference),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = line.description,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.dmc_tariff, line.tariffHeading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.line.quantitiesText())
        }
    }
}

/** The item of the catalogue the goods are: the one chosen, or the search to choose it. */
@Composable
private fun ItemChoice(state: ImportEntryUiState.Form, actions: ImportEntryActions) {
    val chosen = state.item
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = stringResource(R.string.import_item), style = MaterialTheme.typography.bodyLarge)
        if (chosen != null) {
            ItemCard(chosen, onClick = null) {
                TextButton(onClick = { actions.onFormChange { copy(item = null) } }) {
                    Text(stringResource(R.string.import_item_change))
                }
            }
        } else {
            SearchField(state.query, actions.onQueryChange, stringResource(R.string.items_search))
            state.matches.forEach { item ->
                ItemCard(
                    item = item,
                    onClick = {
                        actions.onFormChange { copy(item = item, problems = problems - ImportProblem.ITEM_MISSING) }
                    },
                )
            }
            if (state.matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.import_item_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = actions.onNewItem) { Text(stringResource(R.string.import_item_new)) }
            state.problemText(ImportProblem.ITEM_MISSING)?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    TonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        CardListItem(
            leadingContent = { IconTile(AppIcon.INVENTORY) },
            headlineContent = { Text(item.details.designation) },
            supportingContent = { Text(stringResource(R.string.import_item_code_unit, item.code, item.details.unit)) },
            trailingContent = trailing,
        )
    }
}

@Composable
private fun TypedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    hint: String? = null,
    number: Boolean = true,
) {
    val supporting = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = if (number) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
        } else {
            KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
        },
        singleLine = true,
    )
}

/** @return the message of [problem] when the last attempt broke it, or null. */
@Composable
private fun ImportEntryUiState.Form.problemText(problem: ImportProblem): String? =
    problem.takeIf { it in problems }?.let { stringResource(IMPORT_PROBLEM_MESSAGES.getValue(it)) }
