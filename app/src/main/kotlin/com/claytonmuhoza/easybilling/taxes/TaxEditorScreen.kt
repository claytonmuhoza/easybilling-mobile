package com.claytonmuhoza.easybilling.taxes

import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.ui.dateText

/**
 * Tax editor wired to its view model.
 *
 * @param onClose called to leave the screen, after a creation or when the user goes back.
 * @param viewModel holder of the editor state.
 */
@Composable
fun TaxEditorRoute(onClose: () -> Unit, viewModel: TaxEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == TaxEditorUiState.Created) {
        LaunchedEffect(Unit) { onClose() }
    }
    TaxEditorScreen(
        state = state,
        actions = TaxEditorActions(
            onKindChange = viewModel::onKindChange,
            onNameChange = viewModel::onNameChange,
            onModeChange = { mode -> viewModel.onRateChange { copy(mode = mode) } },
            onValueChange = { value -> viewModel.onRateChange { copy(value = value) } },
            onBaseChange = { base -> viewModel.onRateChange { copy(base = base) } },
            onDateChange = { date -> viewModel.onRateChange { copy(validFrom = date) } },
            onCreate = viewModel::create,
            onSchedule = viewModel::schedule,
            onToggleArchived = viewModel::toggleArchived,
            onRetry = viewModel::load,
            onBack = onClose,
        ),
    )
}

/**
 * What the user can do on the tax editor.
 *
 * @property onKindChange called when the kind of a new tax is picked.
 * @property onNameChange called when the name changes.
 * @property onModeChange called when the way of computing is picked.
 * @property onValueChange called when the rate or amount changes.
 * @property onBaseChange called when the base of a percentage is picked.
 * @property onDateChange called when the first day changes.
 * @property onCreate called when the user asks to create the tax.
 * @property onSchedule called when the user asks to schedule the new rate.
 * @property onToggleArchived called when the user archives the tax or brings it back.
 * @property onRetry called when the user asks to read the tax again.
 * @property onBack called when the user goes back.
 */
class TaxEditorActions(
    val onKindChange: (TaxKind) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onModeChange: (TaxMode) -> Unit = {},
    val onValueChange: (String) -> Unit = {},
    val onBaseChange: (PercentageBase) -> Unit = {},
    val onDateChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onSchedule: () -> Unit = {},
    val onToggleArchived: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Creation of a tax with its first rate, or the rates of an existing tax with its next rate and
 * its archiving (UC-A07).
 *
 * @param state the editor state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TaxEditorScreen(state: TaxEditorUiState, actions: TaxEditorActions, modifier: Modifier = Modifier) {
    val title = (state as? TaxEditorUiState.Editing)?.existing?.name ?: stringResource(R.string.tax_editor_new_title)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            TaxEditorUiState.Loading, TaxEditorUiState.Created -> LoadingState()
            TaxEditorUiState.Failed -> ErrorState(
                message = stringResource(R.string.taxes_load_error),
                onRetry = actions.onRetry,
            )
            TaxEditorUiState.NotFound -> ErrorState(message = stringResource(R.string.tax_not_found))
            is TaxEditorUiState.Editing -> FormColumn {
                if (state.existing == null) NewTaxContent(state, actions) else ExistingTaxContent(state, actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.NewTaxContent(state: TaxEditorUiState.Editing, actions: TaxEditorActions) {
    ChoiceField(
        label = stringResource(R.string.tax_field_kind),
        options = TaxKind.entries.map { it to it.shortLabel() },
        selected = state.kind,
        onSelect = actions.onKindChange,
        errorMessage = problemText(state.problems, TaxProblemFields.kind),
    )
    state.kind?.let { Hint(it.longLabel()) }
    CountedTextField(
        value = state.name,
        onValueChange = actions.onNameChange,
        label = stringResource(R.string.tax_field_name),
        maxLength = TaxForm.MAX_NAME_LENGTH,
        errorMessage = problemText(state.problems, TaxProblemFields.name),
    )
    RateFieldsContent(state, actions)
    SaveFeedback(state)
    Button(onClick = actions.onCreate, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(R.string.tax_create))
    }
}

@Composable
private fun ColumnScope.ExistingTaxContent(state: TaxEditorUiState.Editing, actions: TaxEditorActions) {
    val tax = checkNotNull(state.existing)
    Text(text = tax.kind.longLabel(), style = MaterialTheme.typography.bodyLarge)
    if (tax.archived) Hint(stringResource(R.string.tax_archived_hint))

    SectionHeader(stringResource(R.string.tax_rates))
    val current = tax.versions.filter { !it.validFrom.isAfter(state.today) }.maxByOrNull { it.validFrom }
    tax.versions.sortedByDescending { it.validFrom }.forEach { version ->
        val status = when {
            version == current -> stringResource(R.string.tax_rate_current)
            version.validFrom.isAfter(state.today) -> stringResource(R.string.tax_rate_upcoming)
            else -> null
        }
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            CardListItem(
                headlineContent = { Text(version.computation.describe(tax.kind)) },
                supportingContent = {
                    val from = stringResource(R.string.tax_rate_from, dateText(version.validFrom))
                    Text(listOfNotNull(from, status).joinToString(" · "))
                },
            )
        }
    }

    if (!tax.archived) {
        SectionHeader(stringResource(R.string.tax_new_rate))
        Hint(stringResource(R.string.tax_new_rate_hint))
        RateFieldsContent(state, actions)
        SaveFeedback(state)
        Button(onClick = actions.onSchedule, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(stringResource(R.string.tax_schedule))
        }
    }
    OutlinedButton(onClick = actions.onToggleArchived, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(if (tax.archived) R.string.tax_unarchive else R.string.tax_archive))
    }
}

@Composable
private fun RateFieldsContent(state: TaxEditorUiState.Editing, actions: TaxEditorActions) {
    val kind = state.kind
    val rate = state.rate
    if (kind == TaxKind.VAT) {
        Hint(stringResource(R.string.tax_vat_hint))
    } else {
        ChoiceField(
            label = stringResource(R.string.tax_field_mode),
            options = TaxMode.entries.map { it to it.label() },
            selected = rate.mode,
            onSelect = actions.onModeChange,
            errorMessage = problemText(state.problems, TaxProblemFields.mode),
        )
    }
    if (rate.mode == TaxMode.PERCENTAGE || rate.mode == TaxMode.PER_UNIT) {
        DecimalField(
            value = rate.value,
            onValueChange = actions.onValueChange,
            label = stringResource(
                if (rate.mode == TaxMode.PERCENTAGE) R.string.tax_field_rate else R.string.tax_field_amount,
            ),
            error = problemText(state.problems, TaxProblemFields.value),
        )
    }
    if (kind == TaxKind.FLAT_RATE_WITHHOLDING && rate.mode == TaxMode.PERCENTAGE) {
        ChoiceField(
            label = stringResource(R.string.tax_field_base),
            options = PercentageBase.entries.map { it to it.shortLabel() },
            selected = rate.base,
            onSelect = actions.onBaseChange,
            errorMessage = problemText(state.problems, TaxProblemFields.base),
        )
    }
    DateField(
        value = rate.validFrom,
        onValueChange = actions.onDateChange,
        error = problemText(state.problems, TaxProblemFields.date),
    )
}

@Composable
private fun DecimalField(value: String, onValueChange: (String) -> Unit, label: String, error: String?) {
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
private fun DateField(value: String, onValueChange: (String) -> Unit, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.tax_field_valid_from)) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        singleLine = true,
    )
}

@Composable
private fun SaveFeedback(state: TaxEditorUiState.Editing) {
    when (state.feedback) {
        TaxFeedback.SCHEDULED -> Text(stringResource(R.string.tax_scheduled), color = MaterialTheme.colorScheme.primary)
        TaxFeedback.FAILED -> Text(stringResource(R.string.tax_save_error), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
