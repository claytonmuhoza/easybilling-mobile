package com.claytonmuhoza.easybilling.taxprofile

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileChoice
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType

/**
 * Tax profile form wired to its view model.
 *
 * @param onClose called to leave the screen, after a save or when the user goes back.
 * @param onOpenHistory called when the user asks for the previous versions.
 * @param viewModel holder of the form state.
 */
@Composable
fun TaxProfileRoute(
    onClose: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: TaxProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == TaxProfileUiState.Done) {
        LaunchedEffect(Unit) { onClose() }
    }
    TaxProfileScreen(
        state = state,
        actions = TaxProfileActions(
            onTextChange = viewModel::onTextChange,
            onTaxpayerTypeChange = viewModel::onTaxpayerTypeChange,
            onFiscalCenterChange = viewModel::onFiscalCenterChange,
            onTaxesChange = viewModel::onTaxesChange,
            onSave = viewModel::save,
            onRetry = viewModel::load,
            onBack = onClose,
            onOpenHistory = onOpenHistory,
        ),
    )
}

/**
 * What the user can do on the tax profile form.
 *
 * @property onTextChange called when a text field changes.
 * @property onTaxpayerTypeChange called when the kind of taxpayer is chosen.
 * @property onFiscalCenterChange called when the tax office is chosen.
 * @property onTaxesChange called when a tax registration is switched.
 * @property onSave called when the user asks to save.
 * @property onRetry called when the user asks to read the profile again.
 * @property onBack called when the user goes back.
 * @property onOpenHistory called when the user asks for the previous versions.
 */
class TaxProfileActions(
    val onTextChange: (TaxProfileField, String) -> Unit = { _, _ -> },
    val onTaxpayerTypeChange: (TaxpayerType) -> Unit = {},
    val onFiscalCenterChange: (FiscalCenter) -> Unit = {},
    val onTaxesChange: (TaxRegistrations) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
)

/**
 * Entry and change of the tax profile (UC-A02), with its loading and error states.
 *
 * @param state the form state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TaxProfileScreen(state: TaxProfileUiState, actions: TaxProfileActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.tax_profile_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.tax_profile_history), onClick = actions.onOpenHistory) },
    ) {
        when (state) {
            TaxProfileUiState.Loading, TaxProfileUiState.Done -> LoadingState()
            TaxProfileUiState.LoadFailed -> ErrorState(
                message = stringResource(R.string.tax_profile_load_error),
                onRetry = actions.onRetry,
            )
            is TaxProfileUiState.Editing -> FormColumn { TaxProfileFields(state, actions) }
        }
    }
}

@Composable
private fun ColumnScope.TaxProfileFields(state: TaxProfileUiState.Editing, actions: TaxProfileActions) {
    val form = state.form
    val chooseMessage = stringResource(R.string.tax_profile_choose)

    SectionHeader(stringResource(R.string.tax_profile_section_identity))
    ChoiceField(
        label = stringResource(R.string.tax_profile_taxpayer_type),
        options = TaxpayerType.entries.map { it to it.label() },
        selected = form.taxpayerType,
        onSelect = actions.onTaxpayerTypeChange,
        errorMessage = chooseMessage.takeIf { TaxProfileChoice.TAXPAYER_TYPE in state.missingChoices },
    )
    TaxProfileSections.identity.forEach { TextField(it, state, actions) }

    SectionHeader(stringResource(R.string.tax_profile_section_address))
    TaxProfileSections.address.forEach { TextField(it, state, actions) }

    SectionHeader(stringResource(R.string.tax_profile_section_taxes))
    ChoiceField(
        label = stringResource(R.string.tax_profile_fiscal_center),
        options = FiscalCenter.entries.map { it to it.code },
        selected = form.fiscalCenter,
        onSelect = actions.onFiscalCenterChange,
        errorMessage = chooseMessage.takeIf { TaxProfileChoice.FISCAL_CENTER in state.missingChoices },
    )
    Text(
        text = stringResource(R.string.tax_profile_fiscal_center_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow(
        label = stringResource(R.string.tax_profile_vat),
        checked = form.taxes.vat,
        onCheckedChange = { actions.onTaxesChange(form.taxes.copy(vat = it)) },
    )
    SwitchRow(
        label = stringResource(R.string.tax_profile_consumption_tax),
        checked = form.taxes.consumptionTax,
        onCheckedChange = { actions.onTaxesChange(form.taxes.copy(consumptionTax = it)) },
    )
    SwitchRow(
        label = stringResource(R.string.tax_profile_flat_rate_withholding),
        checked = form.taxes.flatRateWithholding,
        onCheckedChange = { actions.onTaxesChange(form.taxes.copy(flatRateWithholding = it)) },
    )
    TaxProfileSections.activity.forEach { TextField(it, state, actions) }

    if (state.saveFailed) {
        Text(
            text = stringResource(R.string.tax_profile_save_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) {
        Text(stringResource(R.string.tax_profile_save))
    }
}

@Composable
private fun TextField(field: TaxProfileField, state: TaxProfileUiState.Editing, actions: TaxProfileActions) {
    CountedTextField(
        value = state.form.texts[field].orEmpty(),
        onValueChange = { actions.onTextChange(field, it) },
        label = field.label(),
        maxLength = field.maxLength,
        errorMessage = state.problems[field]?.message(),
        hint = if (field.required) null else stringResource(R.string.tax_profile_optional),
        keyboardType = if (field == TaxProfileField.PHONE_NUMBER) KeyboardType.Phone else KeyboardType.Text,
        capitalization = when (field) {
            TaxProfileField.TIN, TaxProfileField.TRADE_NUMBER -> KeyboardCapitalization.Characters
            TaxProfileField.PHONE_NUMBER -> KeyboardCapitalization.None
            else -> KeyboardCapitalization.Words
        },
    )
}
