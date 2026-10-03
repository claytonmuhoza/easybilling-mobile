package com.claytonmuhoza.easybilling.taxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.ui.amountText

/**
 * Preview of a line wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the preview state.
 */
@Composable
fun LinePreviewRoute(onBack: () -> Unit, viewModel: LinePreviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LinePreviewScreen(
        state = state,
        actions = LinePreviewActions(
            onUnitPriceChange = { price -> viewModel.onInputsChange { copy(unitPrice = price) } },
            onQuantityChange = { quantity -> viewModel.onInputsChange { copy(quantity = quantity) } },
            onPriceModeChange = { mode -> viewModel.onInputsChange { copy(priceMode = mode) } },
            onCurrencyChange = { currency -> viewModel.onInputsChange { copy(currency = currency) } },
            onTaxSelected = viewModel::onTaxSelected,
            onFreeAmountChange = viewModel::onFreeAmountChange,
            onRetry = viewModel::load,
            onBack = onBack,
        ),
    )
}

/**
 * What the user can do on the preview of a line.
 *
 * @property onUnitPriceChange called when the unit price changes.
 * @property onQuantityChange called when the quantity changes.
 * @property onPriceModeChange called when the price is said to include the taxes or not.
 * @property onCurrencyChange called when the currency is picked.
 * @property onTaxSelected called with a kind and the tax picked for it, or null for none.
 * @property onFreeAmountChange called when the amount of a free tax changes.
 * @property onRetry called when the user asks to read the taxes again.
 * @property onBack called when the user goes back.
 */
class LinePreviewActions(
    val onUnitPriceChange: (String) -> Unit = {},
    val onQuantityChange: (String) -> Unit = {},
    val onPriceModeChange: (PriceMode) -> Unit = {},
    val onCurrencyChange: (Currency) -> Unit = {},
    val onTaxSelected: (TaxKind, TaxId?) -> Unit = { _, _ -> },
    val onFreeAmountChange: (TaxKind, String) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * A line computed with the formula of the OBR, as it will be sent (§ 7.1 of the architecture).
 *
 * @param state the preview state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun LinePreviewScreen(state: LinePreviewUiState, actions: LinePreviewActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.preview_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            LinePreviewUiState.Loading -> LoadingState()
            LinePreviewUiState.Failed -> ErrorState(
                message = stringResource(R.string.taxes_load_error),
                onRetry = actions.onRetry,
            )
            is LinePreviewUiState.Ready -> FormColumn {
                LineFields(state, actions)
                TaxChoices(state, actions)
                Result(state)
            }
        }
    }
}

@Composable
private fun ColumnScope.LineFields(state: LinePreviewUiState.Ready, actions: LinePreviewActions) {
    SectionHeader(stringResource(R.string.preview_section_line))
    NumberField(state.inputs.unitPrice, actions.onUnitPriceChange, stringResource(R.string.preview_unit_price))
    NumberField(state.inputs.quantity, actions.onQuantityChange, stringResource(R.string.preview_quantity))
    ChoiceField(
        label = stringResource(R.string.preview_price_mode),
        options = listOf(
            PriceMode.EXCLUDING_TAXES to stringResource(R.string.preview_excluding_taxes),
            PriceMode.INCLUDING_TAXES to stringResource(R.string.preview_including_taxes),
        ),
        selected = state.inputs.priceMode,
        onSelect = actions.onPriceModeChange,
    )
    ChoiceField(
        label = stringResource(R.string.preview_currency),
        options = Currency.entries.map { it to it.name },
        selected = state.inputs.currency,
        onSelect = actions.onCurrencyChange,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.TaxChoices(state: LinePreviewUiState.Ready, actions: LinePreviewActions) {
    SectionHeader(stringResource(R.string.preview_section_taxes))
    RegistrationNotice(state)
    TaxKind.entries.forEach { kind ->
        val ofKind = state.taxes.filter { it.kind == kind }
        if (ofKind.isEmpty()) return@forEach
        val selected = state.inputs.selected[kind]
        Text(kind.longLabel(), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            FilterChip(
                selected = selected == null,
                onClick = { actions.onTaxSelected(kind, null) },
                label = { Text(stringResource(R.string.preview_no_tax)) },
            )
            ofKind.forEach { tax ->
                FilterChip(
                    selected = selected == tax.id,
                    onClick = { actions.onTaxSelected(kind, tax.id) },
                    label = { Text(tax.name) },
                )
            }
        }
        val picked = ofKind.firstOrNull { it.id == selected }
        if (picked?.computationAt(state.today) == TaxComputation.FreeAmount) {
            NumberField(
                value = state.inputs.freeAmounts[kind].orEmpty(),
                onValueChange = { actions.onFreeAmountChange(kind, it) },
                label = stringResource(R.string.preview_free_amount, kind.shortLabel()),
            )
        }
    }
}

@Composable
private fun RegistrationNotice(state: LinePreviewUiState.Ready) {
    val registrations = state.registrations
    val notice = if (registrations == null) {
        stringResource(R.string.preview_no_profile)
    } else {
        listOfNotNull(
            TaxKind.VAT.takeUnless { registrations.vat },
            TaxKind.CONSUMPTION_TAX.takeUnless { registrations.consumptionTax },
            TaxKind.FLAT_RATE_WITHHOLDING.takeUnless { registrations.flatRateWithholding },
        ).takeIf { it.isNotEmpty() }?.let { kinds ->
            val names = kinds.map { it.shortLabel() }.joinToString(", ")
            stringResource(R.string.preview_not_registered, names)
        }
    }
    notice?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.Result(state: LinePreviewUiState.Ready) {
    SectionHeader(stringResource(R.string.preview_section_result))
    state.error?.let { error ->
        Text(
            text = stringResource(
                when (error) {
                    PreviewError.INVALID_NUMBER -> R.string.preview_invalid_number
                    PreviewError.NOT_COVERED -> R.string.preview_not_covered
                },
            ),
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.result?.let { AmountRows(it) }
}

@Composable
private fun AmountRows(amounts: LineAmounts) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        AmountRow(stringResource(R.string.amount_unit_price), amounts.unitPrice)
        AmountRow(stringResource(R.string.amount_price), amounts.price)
        AmountRow(stringResource(R.string.amount_ct), amounts.consumptionTax)
        AmountRow(stringResource(R.string.amount_ott), amounts.ott)
        AmountRow(stringResource(R.string.amount_tsce), amounts.tsce)
        AmountRow(stringResource(R.string.amount_nvat), amounts.priceWithoutVat, strong = true)
        AmountRow(stringResource(R.string.amount_vat), amounts.vat)
        AmountRow(stringResource(R.string.amount_wvat), amounts.priceWithVat, strong = true)
        AmountRow(stringResource(R.string.amount_pfl), amounts.flatRateWithholding)
        AmountRow(stringResource(R.string.amount_total), amounts.total, strong = true)
    }
}

@Composable
private fun AmountRow(label: String, amount: Money, strong: Boolean = false) {
    val weight = if (strong) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(text = label, modifier = Modifier.weight(1f), fontWeight = weight)
        Text(text = amountText(amount), fontWeight = weight)
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
    )
}
