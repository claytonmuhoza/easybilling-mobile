package com.claytonmuhoza.easybilling.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReason
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalDialog
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.decimalText

/** Smallest height of a touch target (Material accessibility guideline). */
private val MinTouchTarget = 48.dp

/**
 * Credit note on an invoice wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onIssued called with the identifier of the credit note once it is issued.
 * @param viewModel holder of the credit note state.
 */
@Composable
fun CreditNoteRoute(
    onBack: () -> Unit,
    onIssued: (Long) -> Unit,
    viewModel: CreditNoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    (state as? CreditNoteUiState.Done)?.let { done -> LaunchedEffect(done) { onIssued(done.creditNoteId) } }
    CreditNoteScreen(
        state = state,
        actions = CreditNoteActions(
            onBack = onBack,
            onToggleLine = viewModel.lines::toggle,
            onSelectAll = viewModel.lines::selectAll,
            onQuantityChange = viewModel.lines::onQuantityChange,
            onUnitPriceChange = viewModel.lines::onUnitPriceChange,
            onReasonTypeChange = viewModel::onReasonTypeChange,
            onDetailChange = viewModel::onDetailChange,
            onSubmit = viewModel::submit,
            onRetry = viewModel::load,
        ),
        approvalActions = ApprovalActions(
            onChoose = viewModel.approval::choose,
            onPinChange = viewModel.approval::onPinChange,
            onSubmit = viewModel.approval::submit,
            onCancel = viewModel.approval::cancel,
        ),
    )
}

/**
 * What the user can do on a credit note.
 *
 * @property onBack called when the user goes back.
 * @property onToggleLine called with the item of the line whose credit is turned on or off.
 * @property onSelectAll called when the user credits every line.
 * @property onQuantityChange called with the item of a line and the new content of its quantity.
 * @property onUnitPriceChange called with the item of a line and the new content of its unit price.
 * @property onReasonTypeChange called with the kind of reason chosen.
 * @property onDetailChange called when the detail of the reason changes.
 * @property onSubmit called when the user issues the credit note.
 * @property onRetry called when the user asks to read the invoice again.
 */
class CreditNoteActions(
    val onBack: () -> Unit = {},
    val onToggleLine: (Long) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onQuantityChange: (Long, String) -> Unit = { _, _ -> },
    val onUnitPriceChange: (Long, String) -> Unit = { _, _ -> },
    val onReasonTypeChange: (CreditReasonType) -> Unit = {},
    val onDetailChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * A credit note on an invoice (UC-E03): the lines credited, each with its quantity and its unit
 * price, the reason, and the total against what is left to credit. A user who may not correct an
 * invoice is asked for the PIN of a manager.
 *
 * @param state the credit note state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param approvalActions what the user can do on the approval of a manager.
 */
@Composable
fun CreditNoteScreen(
    state: CreditNoteUiState,
    actions: CreditNoteActions,
    modifier: Modifier = Modifier,
    approvalActions: ApprovalActions = ApprovalActions(),
) {
    TitledScreen(title = stringResource(R.string.credit_note_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            CreditNoteUiState.Loading, is CreditNoteUiState.Done -> LoadingState()
            CreditNoteUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoice_cancel_load_error),
                onRetry = actions.onRetry,
            )
            is CreditNoteUiState.NotCreditable -> ErrorState(message = state.problem.message())
            is CreditNoteUiState.Form -> {
                state.approval?.let { approval ->
                    ApprovalDialog(
                        message = stringResource(R.string.credit_note_approval, state.number),
                        state = approval,
                        actions = approvalActions,
                        busy = state.busy,
                    )
                }
                CreditForm(state, actions)
            }
        }
    }
}

@Composable
private fun CreditForm(state: CreditNoteUiState.Form, actions: CreditNoteActions) {
    FormColumn {
        Help(stringResource(R.string.credit_note_help, state.number, amountText(state.remainingAmount)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.credit_note_lines), modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onSelectAll, enabled = !state.busy) {
                Text(stringResource(R.string.credit_note_select_all))
            }
        }
        state.lines.forEach { CreditLine(it, actions, enabled = !state.busy) }
        ReasonChoice(state, actions)
        CountedTextField(
            value = state.detail,
            onValueChange = actions.onDetailChange,
            label = stringResource(R.string.credit_note_detail),
            maxLength = state.reasonType?.let(CreditReason::maxDetailLength) ?: SHORTEST_DETAIL,
            errorMessage = stringResource(R.string.credit_note_detail_too_long)
                .takeIf { state.reasonProblem == ReasonProblem.TOO_LONG },
            hint = stringResource(R.string.credit_note_detail_hint),
            capitalization = KeyboardCapitalization.Sentences,
        )
        Fact(stringResource(R.string.credit_note_total), amountText(state.total))
        if (state.aboveCap) {
            val left = amountText(state.remainingAmount)
            StatusBanner(StatusTone.ERROR, stringResource(R.string.credit_note_above_cap, left))
        }
        state.refusal?.let { StatusBanner(StatusTone.ERROR, it.message()) }
        state.problem?.let { StatusBanner(StatusTone.ERROR, it.message()) }
        Button(onClick = actions.onSubmit, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(stringResource(R.string.credit_note_issue))
        }
    }
}

/** A line of the invoice: ticked to credit it, then its quantity, its unit price and what it credits. */
@Composable
private fun CreditLine(line: CreditLineUi, actions: CreditNoteActions, enabled: Boolean) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(
                    value = line.selected,
                    enabled = enabled && line.creditable,
                    role = Role.Checkbox,
                    onValueChange = { actions.onToggleLine(line.itemId) },
                )
                .padding(Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = line.selected, onCheckedChange = null, enabled = enabled && line.creditable)
            Column(modifier = Modifier.weight(1f)) {
                Text(line.designation)
                Text(
                    text = if (line.creditable) {
                        stringResource(
                            R.string.credit_note_line_left,
                            decimalText(line.sold),
                            decimalText(line.remaining),
                        )
                    } else {
                        stringResource(R.string.credit_note_line_credited, decimalText(line.sold))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (line.selected) CreditLineFields(line, actions, enabled)
    }
}

/** What a ticked line credits: its quantity, its unit price, and the total they give. */
@Composable
private fun CreditLineFields(line: CreditLineUi, actions: CreditNoteActions, enabled: Boolean) {
    Column(
        modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        NumberField(
            value = line.quantity,
            onValueChange = { actions.onQuantityChange(line.itemId, it) },
            label = stringResource(R.string.credit_note_quantity),
            error = line.problems.firstNotNullOfOrNull { it.quantityMessage() },
            enabled = enabled,
        )
        NumberField(
            value = line.unitPrice,
            onValueChange = { actions.onUnitPriceChange(line.itemId, it) },
            label = stringResource(
                if (line.includingTaxes) {
                    R.string.credit_note_unit_price_with_taxes
                } else {
                    R.string.credit_note_unit_price_without_taxes
                },
            ),
            error = line.problems.firstNotNullOfOrNull { it.priceMessage() },
            enabled = enabled,
        )
        line.total?.let { Fact(stringResource(R.string.credit_note_line_total), amountText(it)) }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        singleLine = true,
    )
}

/** The kinds of reason, one radio button each; the whole row is the touch target. */
@Composable
private fun ReasonChoice(state: CreditNoteUiState.Form, actions: CreditNoteActions) {
    SectionHeader(stringResource(R.string.credit_note_reason))
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        CreditReasonType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .selectable(
                        selected = type == state.reasonType,
                        enabled = !state.busy,
                        role = Role.RadioButton,
                        onClick = { actions.onReasonTypeChange(type) },
                    ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == state.reasonType, onClick = null, enabled = !state.busy)
                Text(type.label())
            }
        }
    }
    if (state.reasonProblem == ReasonProblem.BLANK) {
        Text(
            text = stringResource(R.string.credit_note_reason_blank),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The shortest detail every kind of reason leaves room for: the limit shown before a kind is chosen. */
private val SHORTEST_DETAIL = CreditReasonType.entries.minOf(CreditReason::maxDetailLength)
