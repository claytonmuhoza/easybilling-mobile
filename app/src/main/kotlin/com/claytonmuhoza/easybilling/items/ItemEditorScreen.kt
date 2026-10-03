package com.claytonmuhoza.easybilling.items

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.catalog.ItemField
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.catalog.ItemProblem
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.taxes.shortLabel

/**
 * Item editor wired to its view model.
 *
 * @param onClose called to leave the screen, after a save or when the user goes back.
 * @param viewModel holder of the editor state.
 */
@Composable
fun ItemEditorRoute(onClose: () -> Unit, viewModel: ItemEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == ItemEditorUiState.Saved) {
        LaunchedEffect(Unit) { onClose() }
    }
    ItemEditorScreen(
        state = state,
        actions = ItemEditorActions(
            onFormChange = viewModel::onFormChange,
            onSave = viewModel::save,
            onToggleArchived = viewModel::toggleArchived,
            onRetry = viewModel::load,
            onBack = onClose,
        ),
    )
}

/**
 * What the user can do on the item editor.
 *
 * @property onFormChange called with the change of one field.
 * @property onSave called when the user asks to create or save the item.
 * @property onToggleArchived called when the user archives the item or brings it back.
 * @property onRetry called when the user asks to read the item again.
 * @property onBack called when the user goes back.
 */
class ItemEditorActions(
    val onFormChange: (ItemForm.() -> ItemForm) -> Unit = {},
    val onSave: () -> Unit = {},
    val onToggleArchived: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Creation or change of an item, with its taxes and its archiving (UC-B01, UC-B04).
 *
 * @param state the editor state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ItemEditorScreen(state: ItemEditorUiState, actions: ItemEditorActions, modifier: Modifier = Modifier) {
    val existing = (state as? ItemEditorUiState.Editing)?.existing
    val title = existing?.details?.designation ?: stringResource(R.string.item_new_title)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            ItemEditorUiState.Loading, ItemEditorUiState.Saved -> LoadingState()
            ItemEditorUiState.Failed -> ErrorState(
                message = stringResource(R.string.items_load_error),
                onRetry = actions.onRetry,
            )
            ItemEditorUiState.NotFound -> ErrorState(message = stringResource(R.string.item_not_found))
            is ItemEditorUiState.Editing -> FormColumn {
                Notices(state)
                IdentityFields(state, actions)
                PriceAndStockFields(state, actions)
                TaxFields(state, actions)
                SaveButtons(state, actions)
            }
        }
    }
}

/** What the user must know before reading the fields: an archived item, a VAT to choose. */
@Composable
private fun Notices(state: ItemEditorUiState.Editing) {
    val item = state.existing ?: return
    if (item.archived) Hint(stringResource(R.string.item_archived_hint))
    if (item.needsVat(state.rules.registrations)) {
        Text(text = stringResource(R.string.item_vat_needed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun IdentityFields(state: ItemEditorUiState.Editing, actions: ItemEditorActions) {
    val form = state.form
    if (state.existing == null) {
        CountedTextField(
            value = form.code,
            onValueChange = { code -> actions.onFormChange { copy(code = code.uppercase()) } },
            label = stringResource(R.string.item_field_code),
            maxLength = ItemField.CODE.maxLength,
            errorMessage = state.problemText(ItemProblemFields.code),
            hint = stringResource(R.string.item_code_hint),
            capitalization = KeyboardCapitalization.Characters,
        )
    } else {
        Text(
            text = stringResource(R.string.item_code_fixed, state.existing.code),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    CountedTextField(
        value = form.designation,
        onValueChange = { designation -> actions.onFormChange { copy(designation = designation) } },
        label = stringResource(R.string.item_field_designation),
        maxLength = ItemField.DESIGNATION.maxLength,
        errorMessage = state.problemText(ItemProblemFields.designation),
    )
    CountedTextField(
        value = form.unit,
        onValueChange = { unit -> actions.onFormChange { copy(unit = unit) } },
        label = stringResource(R.string.item_field_unit),
        maxLength = ItemField.UNIT.maxLength,
        errorMessage = state.problemText(ItemProblemFields.unit),
        hint = stringResource(R.string.item_unit_hint),
        capitalization = KeyboardCapitalization.None,
    )
    // A Bluetooth reader types like a keyboard: scanning with the field selected fills it.
    CountedTextField(
        value = form.barcode,
        onValueChange = { barcode -> actions.onFormChange { copy(barcode = barcode) } },
        label = stringResource(R.string.item_field_barcode),
        maxLength = ItemForm.MAX_BARCODE_LENGTH,
        errorMessage = state.problemText(ItemProblemFields.barcode),
        hint = stringResource(R.string.item_barcode_hint),
        capitalization = KeyboardCapitalization.None,
    )
}

@Composable
private fun PriceAndStockFields(state: ItemEditorUiState.Editing, actions: ItemEditorActions) {
    val form = state.form
    val priceError = state.problemText(ItemProblemFields.price)
    OutlinedTextField(
        value = form.price,
        onValueChange = { price -> actions.onFormChange { copy(price = price) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.item_field_price)) },
        supportingText = priceError?.let { { Text(it) } },
        isError = priceError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
    )
    ChoiceField(
        label = stringResource(R.string.item_field_price_mode),
        options = PriceMode.entries.map { it to it.label() },
        selected = form.priceMode,
        onSelect = { mode -> actions.onFormChange { copy(priceMode = mode) } },
        errorMessage = state.problemText(ItemProblemFields.priceMode),
    )
    ChoiceField(
        label = stringResource(R.string.item_field_stock),
        options = listOf(true, false).map { it to stockLabel(it) },
        selected = form.stockTracked,
        onSelect = { tracked -> actions.onFormChange { copy(stockTracked = tracked) } },
        errorMessage = state.problemText(ItemProblemFields.stock),
    )
    Hint(stringResource(R.string.item_stock_hint))
}

/**
 * One choice per kind of tax that can apply: the taxes the business is registered for, and the
 * ones the specification applies to everyone. A kind without any active tax is not shown, except
 * the VAT of a registered business, which is required.
 */
@Composable
private fun TaxFields(state: ItemEditorUiState.Editing, actions: ItemEditorActions) {
    val registrations = state.rules.registrations
    val offered = TaxKind.entries.filter { kind ->
        when (kind) {
            TaxKind.VAT -> registrations.vat
            TaxKind.CONSUMPTION_TAX -> registrations.consumptionTax
            TaxKind.FLAT_RATE_WITHHOLDING -> registrations.flatRateWithholding
            TaxKind.OTT, TaxKind.TSCE -> true
        }
    }
    val choices = offered.associateWith { kind -> state.rules.taxes.filter { it.kind == kind && !it.archived } }
        .filter { (kind, taxes) -> taxes.isNotEmpty() || kind == TaxKind.VAT }
    if (choices.isEmpty()) return

    SectionHeader(stringResource(R.string.taxes_title))
    choices.forEach { (kind, taxes) -> TaxChoice(kind, taxes, state, actions) }
    state.problemText(ItemProblemFields.taxes)?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun TaxChoice(
    kind: TaxKind,
    taxes: List<TaxDefinition>,
    state: ItemEditorUiState.Editing,
    actions: ItemEditorActions,
) {
    val required = kind == TaxKind.VAT
    if (required && taxes.isEmpty()) {
        Text(text = stringResource(R.string.item_no_vat_defined), color = MaterialTheme.colorScheme.error)
        return
    }
    // The VAT of a registered business is required: it has no "None".
    val none: List<Pair<TaxId?, String>> =
        if (required) emptyList() else listOf(null to stringResource(R.string.item_tax_none))
    ChoiceField(
        label = kind.shortLabel(),
        options = none + taxes.map { it.id to it.name },
        selected = state.form.taxes[kind],
        onSelect = { id ->
            actions.onFormChange { copy(taxes = if (id == null) this.taxes - kind else this.taxes + (kind to id)) }
        },
        errorMessage = if (required) state.problemText(ItemProblemFields.vat) else null,
    )
}

@Composable
private fun SaveButtons(state: ItemEditorUiState.Editing, actions: ItemEditorActions) {
    if (state.failed) {
        Text(text = stringResource(R.string.item_save_error), color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(if (state.existing == null) R.string.item_create else R.string.item_save))
    }
    val item = state.existing ?: return
    OutlinedButton(onClick = actions.onToggleArchived, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(if (item.archived) R.string.item_unarchive else R.string.item_archive))
    }
}

/** @return the message of the first broken rule shown under a field, or null. */
@Composable
private fun ItemEditorUiState.Editing.problemText(field: Set<ItemProblem>): String? =
    problems.firstOrNull { it in field }?.message(rules.codePrefix)

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
