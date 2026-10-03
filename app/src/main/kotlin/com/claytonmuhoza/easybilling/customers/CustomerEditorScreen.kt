package com.claytonmuhoza.easybilling.customers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.CustomerField
import com.claytonmuhoza.easybilling.domain.customer.CustomerForm
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerProblem
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Customer editor wired to its view model.
 *
 * @param onClose called to leave the screen when the user goes back.
 * @param onSaved called with the identifier of the customer once it is stored; it leaves the screen.
 * @param viewModel holder of the editor state.
 */
@Composable
fun CustomerEditorRoute(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit = { onClose() },
    viewModel: CustomerEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    (state as? CustomerEditorUiState.Saved)?.let { saved -> LaunchedEffect(saved) { onSaved(saved.id) } }
    CustomerEditorScreen(
        state = state,
        actions = CustomerEditorActions(
            onFormChange = viewModel::onFormChange,
            onCheckTin = viewModel::checkTin,
            onSave = viewModel::save,
            onToggleArchived = viewModel::toggleArchived,
            onRetry = viewModel::load,
            onBack = onClose,
        ),
    )
}

/**
 * What the user can do on the customer editor.
 *
 * @property onFormChange called with the change of one field.
 * @property onCheckTin called when the user asks the OBR about the typed TIN.
 * @property onSave called when the user asks to create or save the customer.
 * @property onToggleArchived called when the user archives the customer or brings it back.
 * @property onRetry called when the user asks to read the customer again.
 * @property onBack called when the user goes back.
 */
class CustomerEditorActions(
    val onFormChange: (CustomerForm.() -> CustomerForm) -> Unit = {},
    val onCheckTin: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onToggleArchived: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Creation or change of a customer, with the check of its TIN and its archiving (UC-B05, UC-B06).
 *
 * @param state the editor state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun CustomerEditorScreen(
    state: CustomerEditorUiState,
    actions: CustomerEditorActions,
    modifier: Modifier = Modifier,
) {
    val existing = (state as? CustomerEditorUiState.Editing)?.existing
    val title = existing?.details?.name ?: stringResource(R.string.customer_new_title)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            CustomerEditorUiState.Loading, is CustomerEditorUiState.Saved -> LoadingState()
            CustomerEditorUiState.Failed -> ErrorState(
                message = stringResource(R.string.customers_load_error),
                onRetry = actions.onRetry,
            )
            CustomerEditorUiState.NotFound -> ErrorState(message = stringResource(R.string.customer_not_found))
            is CustomerEditorUiState.Editing -> FormColumn {
                if (state.counter) {
                    Hint(stringResource(R.string.customer_counter_hint))
                    NameField(state, actions)
                } else {
                    if (state.existing?.archived == true) Hint(stringResource(R.string.customer_archived_hint))
                    KindFields(state, actions)
                    TinFields(state, actions)
                    NameField(state, actions)
                    ContactFields(state, actions)
                }
                SaveButtons(state, actions)
            }
        }
    }
}

@Composable
private fun KindFields(state: CustomerEditorUiState.Editing, actions: CustomerEditorActions) {
    ChoiceField(
        label = stringResource(R.string.customer_field_origin),
        options = CustomerOrigin.entries.map { it to it.label() },
        selected = state.form.origin,
        onSelect = { origin -> actions.onFormChange { copy(origin = origin) } },
        errorMessage = state.problemText(CustomerProblemFields.origin),
    )
    ChoiceField(
        label = stringResource(R.string.customer_field_person_type),
        options = TaxpayerType.entries.map { it to it.customerLabel() },
        selected = state.form.personType,
        onSelect = { type -> actions.onFormChange { copy(personType = type) } },
        errorMessage = state.problemText(CustomerProblemFields.personType),
    )
}

/** The TIN of a local customer, what the OBR said of it and the button that asks the OBR. */
@Composable
private fun TinFields(state: CustomerEditorUiState.Editing, actions: CustomerEditorActions) {
    if (state.form.origin == CustomerOrigin.FOREIGN) {
        Hint(stringResource(R.string.customer_tin_foreign))
        return
    }
    val mandatory = state.form.personType == TaxpayerType.LEGAL_PERSON
    CountedTextField(
        value = state.form.tin,
        onValueChange = { tin -> actions.onFormChange { copy(tin = tin) } },
        label = stringResource(R.string.customer_field_tin),
        maxLength = CustomerField.TIN.maxLength,
        errorMessage = state.problemText(CustomerProblemFields.tin),
        hint = stringResource(
            if (mandatory) R.string.customer_tin_hint_mandatory else R.string.customer_tin_hint_optional,
        ),
        capitalization = KeyboardCapitalization.None,
    )
    TinStatus(state)
    OutlinedButton(onClick = actions.onCheckTin, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(R.string.customer_tin_check))
    }
    Hint(stringResource(R.string.customer_tin_rule))
}

/** What is known of the typed TIN: being checked, just checked, or checked when it was saved. */
@Composable
private fun TinStatus(state: CustomerEditorUiState.Editing) {
    val stored = state.existing?.details?.tin
    when (val notice = state.tinNotice) {
        null -> when {
            state.checkingTin -> Hint(stringResource(R.string.customer_tin_checking))
            stored != null && stored.number == state.form.tin.trim() -> Text(
                text = stringResource(R.string.customer_tin_verified, dateTimeText(stored.verifiedAt), stored.obrName),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is TinNotice.Recognised -> Text(
            text = stringResource(R.string.customer_tin_recognised, notice.name),
            color = MaterialTheme.colorScheme.primary,
        )
        TinNotice.Unknown -> ErrorText(stringResource(R.string.customer_tin_unknown))
        is TinNotice.NotChecked -> ErrorText(notice.failure.message(notice.message))
    }
}

@Composable
private fun NameField(state: CustomerEditorUiState.Editing, actions: CustomerEditorActions) {
    val obrGivesIt = !state.counter && state.form.origin == CustomerOrigin.LOCAL
    CountedTextField(
        value = state.form.name,
        onValueChange = { name -> actions.onFormChange { copy(name = name) } },
        label = stringResource(R.string.customer_field_name),
        maxLength = CustomerField.NAME.maxLength,
        errorMessage = state.problemText(CustomerProblemFields.name),
        hint = if (obrGivesIt) stringResource(R.string.customer_name_hint) else null,
        capitalization = KeyboardCapitalization.Words,
    )
}

@Composable
private fun ContactFields(state: CustomerEditorUiState.Editing, actions: CustomerEditorActions) {
    CountedTextField(
        value = state.form.address,
        onValueChange = { address -> actions.onFormChange { copy(address = address) } },
        label = stringResource(R.string.customer_field_address),
        maxLength = CustomerField.ADDRESS.maxLength,
        errorMessage = state.problemText(CustomerProblemFields.address),
    )
    CountedTextField(
        value = state.form.phone,
        onValueChange = { phone -> actions.onFormChange { copy(phone = phone) } },
        label = stringResource(R.string.customer_field_phone),
        maxLength = CustomerForm.MAX_PHONE_LENGTH,
        errorMessage = state.problemText(CustomerProblemFields.phone),
        hint = stringResource(R.string.customer_phone_hint),
        keyboardType = KeyboardType.Phone,
    )
    // A foreign customer has no TIN, so it cannot be registered for the VAT.
    if (state.form.origin != CustomerOrigin.FOREIGN) {
        SwitchRow(
            label = stringResource(R.string.customer_field_vat_payer),
            checked = state.form.vatPayer,
            onCheckedChange = { vatPayer -> actions.onFormChange { copy(vatPayer = vatPayer) } },
        )
        state.problemText(CustomerProblemFields.vatPayer)?.let { ErrorText(it) }
    }
}

@Composable
private fun SaveButtons(state: CustomerEditorUiState.Editing, actions: CustomerEditorActions) {
    if (state.failed) ErrorText(stringResource(R.string.customer_save_error))
    Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(if (state.existing == null) R.string.customer_create else R.string.customer_save))
    }
    val customer = state.existing?.takeIf { !it.counter } ?: return
    OutlinedButton(onClick = actions.onToggleArchived, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
        Text(stringResource(if (customer.archived) R.string.customer_unarchive else R.string.customer_archive))
    }
}

/** @return the message of the first broken rule shown under a field, or null. */
@Composable
private fun CustomerEditorUiState.Editing.problemText(field: Set<CustomerProblem>): String? =
    problems.firstOrNull { it in field }?.message()

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error)
}
