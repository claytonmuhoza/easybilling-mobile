package com.claytonmuhoza.easybilling.numbering

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Numbering screen wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the numbering state.
 */
@Composable
fun NumberingRoute(onBack: () -> Unit, viewModel: NumberingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NumberingScreen(
        state = state,
        actions = NumberingActions(
            onFieldsChange = viewModel::onFieldsChange,
            onSave = viewModel::save,
            onRetry = viewModel::load,
            onBack = onBack,
        ),
    )
}

/**
 * What the user can do on the numbering screen.
 *
 * @property onFieldsChange called with the change of one field.
 * @property onSave called when the user asks to save the numbering.
 * @property onRetry called when the user asks to read the numbering again.
 * @property onBack called when the user goes back.
 */
class NumberingActions(
    val onFieldsChange: (NumberingFields.() -> NumberingFields) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Composition of the numbers of the documents, field by field, with the next numbers (UC-A06).
 * Read only once a number was given.
 *
 * @param state the numbering state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun NumberingScreen(state: NumberingUiState, actions: NumberingActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.numbering_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            NumberingUiState.Loading -> LoadingState()
            NumberingUiState.Failed -> ErrorState(
                message = stringResource(R.string.numbering_load_error),
                onRetry = actions.onRetry,
            )
            is NumberingUiState.Editing -> FormColumn { EditingContent(state, actions) }
            is NumberingUiState.Locked -> FormColumn { LockedContent(state) }
        }
    }
}

@Composable
private fun EditingContent(state: NumberingUiState.Editing, actions: NumberingActions) {
    Hint(stringResource(R.string.numbering_intro))
    // The fields are longer than a phone screen: the number they give stays in sight at the top.
    state.preview?.numbers?.get(DocumentType.FN)?.let { number ->
        Text(text = stringResource(R.string.numbering_example, number), style = MaterialTheme.typography.titleMedium)
    }
    DeviceAndPrefixFields(state, actions)
    NumberShapeFields(state, actions)

    PreviewContent(state.preview)
    (state.problems - NumberingProblemFields.all).forEach { problem ->
        Text(text = problem.message(), color = MaterialTheme.colorScheme.error)
    }
    Hint(stringResource(R.string.numbering_lock_hint))
    when (state.feedback) {
        NumberingFeedback.SAVED ->
            Text(stringResource(R.string.numbering_saved), color = MaterialTheme.colorScheme.primary)
        NumberingFeedback.FAILED ->
            Text(stringResource(R.string.numbering_save_error), color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
    Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(R.string.numbering_save))
    }
}

@Composable
private fun DeviceAndPrefixFields(state: NumberingUiState.Editing, actions: NumberingActions) {
    val fields = state.fields
    SwitchRow(
        label = stringResource(R.string.numbering_multiple_devices),
        checked = fields.multipleDevices,
        onCheckedChange = { checked -> actions.onFieldsChange { copy(multipleDevices = checked) } },
    )
    if (fields.multipleDevices) {
        CountedTextField(
            value = fields.deviceCode,
            onValueChange = { code -> actions.onFieldsChange { copy(deviceCode = code.uppercase()) } },
            label = stringResource(R.string.numbering_device_code),
            maxLength = NumberingForm.MAX_DEVICE_CODE_LENGTH,
            errorMessage = problemText(state.problems, NumberingProblemFields.deviceCode),
            hint = stringResource(R.string.numbering_device_code_hint),
            capitalization = KeyboardCapitalization.Characters,
        )
    }
    CountedTextField(
        value = fields.prefix,
        onValueChange = { prefix -> actions.onFieldsChange { copy(prefix = prefix) } },
        label = stringResource(R.string.numbering_prefix),
        maxLength = NumberingFields.MAX_PREFIX_LENGTH,
        errorMessage = problemText(state.problems, NumberingProblemFields.prefix),
        hint = stringResource(R.string.numbering_prefix_hint),
        capitalization = KeyboardCapitalization.Characters,
    )
}

@Composable
private fun NumberShapeFields(state: NumberingUiState.Editing, actions: NumberingActions) {
    val fields = state.fields
    ChoiceField(
        label = stringResource(R.string.numbering_separator),
        options = NumberingFields.SEPARATORS.map { it to separatorLabel(it) },
        selected = fields.separator,
        onSelect = { separator -> actions.onFieldsChange { copy(separator = separator) } },
    )
    ChoiceField(
        label = stringResource(R.string.numbering_counter_digits),
        options = NumberingFields.COUNTER_DIGITS.map { it to it.toString() },
        selected = fields.counterDigits,
        onSelect = { digits -> actions.onFieldsChange { copy(counterDigits = digits) } },
    )
    ChoiceField(
        label = stringResource(R.string.numbering_year),
        options = YearFormat.entries.map { it to it.label() },
        selected = fields.year,
        onSelect = { year -> actions.onFieldsChange { copy(year = year) } },
    )
    if (fields.year != YearFormat.NONE) {
        ChoiceField(
            label = stringResource(R.string.numbering_year_separator),
            options = NumberingFields.SEPARATORS.map { it to separatorLabel(it) },
            selected = fields.yearSeparator,
            onSelect = { separator -> actions.onFieldsChange { copy(yearSeparator = separator) } },
        )
    }
    ChoiceField(
        label = stringResource(R.string.numbering_reset),
        options = CounterReset.entries.map { it to it.label() },
        selected = fields.reset,
        onSelect = { reset -> actions.onFieldsChange { copy(reset = reset) } },
        errorMessage = problemText(state.problems, NumberingProblemFields.reset),
    )
}
@Composable
private fun LockedContent(state: NumberingUiState.Locked) {
    Text(
        text = stringResource(R.string.numbering_locked, dateTimeText(state.lockedAt)),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.numbering_reset)) },
            supportingContent = { Text(state.settings.reset.label()) },
        )
        if (state.settings.deviceCode.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.numbering_device_code)) },
                supportingContent = { Text(state.settings.deviceCode) },
            )
        }
    }
    PreviewContent(state.preview)
}

@Composable
private fun PreviewContent(preview: NumberingPreview?) {
    SectionHeader(stringResource(R.string.numbering_preview))
    if (preview == null) {
        Hint(stringResource(R.string.numbering_preview_unavailable))
        return
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        preview.numbers.forEach { (type, number) ->
            ListItem(headlineContent = { Text(number) }, overlineContent = { Text(type.label()) })
        }
    }
    SectionHeader(stringResource(R.string.numbering_identifier))
    if (preview.identifier == null) {
        Hint(stringResource(R.string.numbering_identifier_unknown))
    } else {
        Text(text = preview.identifier, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
