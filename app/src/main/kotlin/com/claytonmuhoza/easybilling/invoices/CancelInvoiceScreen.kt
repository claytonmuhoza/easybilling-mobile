package com.claytonmuhoza.easybilling.invoices

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationKind
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationReason
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalDialog

/**
 * Cancellation of an invoice wired to its view model.
 *
 * @param onClose called to leave the screen: the user went back, or the cancellation waits for the OBR.
 * @param onReplaced called once the sale that replaces the invoice is the one being typed.
 * @param viewModel holder of the cancellation state.
 */
@Composable
fun CancelInvoiceRoute(
    onClose: () -> Unit,
    onReplaced: () -> Unit,
    viewModel: CancelInvoiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    (state as? CancelInvoiceUiState.Done)?.let { done ->
        LaunchedEffect(done) { if (done.kind == CancellationKind.REPLACE) onReplaced() else onClose() }
    }
    CancelInvoiceScreen(
        state = state,
        actions = CancelInvoiceActions(
            onBack = onClose,
            onReasonChange = viewModel::onReasonChange,
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
 * What the user can do on the cancellation of an invoice.
 *
 * @property onBack called when the user goes back.
 * @property onReasonChange called when the reason changes.
 * @property onSubmit called with what the user asks for: cancel, or cancel and replace.
 * @property onRetry called when the user asks to read the invoice again.
 */
class CancelInvoiceActions(
    val onBack: () -> Unit = {},
    val onReasonChange: (String) -> Unit = {},
    val onSubmit: (CancellationKind) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The cancellation of an invoice (UC-E01, E02): the reason, then cancel it alone or replace it
 * with a new invoice. A user who may not correct an invoice is asked for the PIN of a manager.
 *
 * @param state the cancellation state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param approvalActions what the user can do on the approval of a manager.
 */
@Composable
fun CancelInvoiceScreen(
    state: CancelInvoiceUiState,
    actions: CancelInvoiceActions,
    modifier: Modifier = Modifier,
    approvalActions: ApprovalActions = ApprovalActions(),
) {
    TitledScreen(title = stringResource(R.string.invoice_cancel), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            CancelInvoiceUiState.Loading, is CancelInvoiceUiState.Done -> LoadingState()
            CancelInvoiceUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoice_cancel_load_error),
                onRetry = actions.onRetry,
            )
            is CancelInvoiceUiState.NotCancellable -> ErrorState(message = state.problem.message())
            is CancelInvoiceUiState.Form -> {
                state.pending?.let { pending ->
                    ApprovalDialog(
                        message = stringResource(R.string.invoice_cancel_approval, state.number),
                        state = pending.dialog,
                        actions = approvalActions,
                        busy = state.busy,
                    )
                }
                CancelForm(state, actions)
            }
        }
    }
}

@Composable
private fun CancelForm(state: CancelInvoiceUiState.Form, actions: CancelInvoiceActions) {
    FormColumn {
        Text(
            text = stringResource(R.string.invoice_cancel_help, state.number),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CountedTextField(
            value = state.reason,
            onValueChange = actions.onReasonChange,
            label = stringResource(R.string.invoice_cancel_reason),
            maxLength = CancellationReason.MAX_LENGTH,
            errorMessage = state.reasonProblem?.message(),
            capitalization = KeyboardCapitalization.Sentences,
        )
        state.refusal?.let { StatusBanner(StatusTone.ERROR, it.message()) }
        if (state.failed) StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_action_failed))
        Button(
            onClick = { actions.onSubmit(CancellationKind.CANCEL) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.invoice_cancel_only))
        }
        OutlinedButton(
            onClick = { actions.onSubmit(CancellationKind.REPLACE) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.invoice_cancel_replace))
        }
        Text(
            text = stringResource(R.string.invoice_cancel_replace_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReasonProblem.message(): String = stringResource(
    when (this) {
        ReasonProblem.BLANK -> R.string.invoice_cancel_reason_blank
        ReasonProblem.TOO_LONG -> R.string.invoice_cancel_reason_too_long
    },
)

@Composable
private fun CancellationProblem.message(): String = stringResource(
    when (this) {
        CancellationProblem.NOT_FOUND -> R.string.invoice_not_found
        CancellationProblem.REJECTED -> R.string.invoice_cancel_problem_rejected
        CancellationProblem.CANCELLED -> R.string.invoice_cancel_problem_cancelled
        CancellationProblem.CANCELLING -> R.string.invoice_cancel_problem_cancelling
        CancellationProblem.REPLACING -> R.string.invoice_cancel_problem_replacing
    },
)
