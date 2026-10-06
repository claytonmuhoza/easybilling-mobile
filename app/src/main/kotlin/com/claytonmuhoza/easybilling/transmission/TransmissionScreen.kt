package com.claytonmuhoza.easybilling.transmission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.palette
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers
import com.claytonmuhoza.easybilling.domain.transmission.QueuedDocument
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.dateTimeText
import java.time.Duration

/**
 * Transmission screen wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenInvoice called with the identifier of the invoice the user opens.
 * @param onOpenExchanges called when the user opens the log of the exchanges with the OBR.
 * @param viewModel holder of the transmission state.
 */
@Composable
fun TransmissionRoute(
    onBack: () -> Unit,
    onOpenInvoice: (Long) -> Unit,
    onOpenExchanges: () -> Unit,
    viewModel: TransmissionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransmissionScreen(
        state = state,
        actions = TransmissionActions(
            onBack = onBack,
            onOpenInvoice = onOpenInvoice,
            onOpenExchanges = onOpenExchanges,
            onRetryNow = viewModel::retryNow,
            onReload = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the transmission screen.
 *
 * @property onBack called when the user goes back.
 * @property onOpenInvoice called with the identifier of the invoice the user opens.
 * @property onOpenExchanges called when the user opens the log of the exchanges with the OBR.
 * @property onRetryNow called when the user asks to try the queue now.
 * @property onReload called when the user asks to read the state again.
 */
class TransmissionActions(
    val onBack: () -> Unit = {},
    val onOpenInvoice: (Long) -> Unit = {},
    val onOpenExchanges: () -> Unit = {},
    val onRetryNow: () -> Unit = {},
    val onReload: () -> Unit = {},
)

/**
 * The transmission to the OBR (UC-D02, D03, D05): the network, what waits and when it is tried
 * next, the invoices the OBR refused with its message, and the queue in the order it is sent.
 *
 * @param state the transmission state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TransmissionScreen(state: TransmissionUiState, actions: TransmissionActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.transmission_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.ebms_exchanges), onClick = actions.onOpenExchanges) },
    ) {
        when (state) {
            TransmissionUiState.Loading -> LoadingState()
            TransmissionUiState.Failed -> ErrorState(
                message = stringResource(R.string.transmission_load_error),
                onRetry = actions.onReload,
            )
            is TransmissionUiState.Ready -> FormColumn {
                Situation(state, actions.onRetryNow)
                Counters(state)
                if (state.status.rejected.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.transmission_to_deal_with))
                    state.status.rejected.forEach { RejectedCard(it, actions.onOpenInvoice) }
                }
                SectionHeader(stringResource(R.string.transmission_queue))
                if (state.status.queue.isEmpty()) {
                    Text(
                        text = stringResource(R.string.transmission_queue_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.status.queue.forEachIndexed { index, document -> QueuedCard(index + 1, document) }
            }
        }
    }
}

/** The network, what happens next, and the button that does not wait. */
@Composable
private fun Situation(state: TransmissionUiState.Ready, onRetryNow: () -> Unit) {
    val palette = state.tone().palette()
    val status = state.status
    TonalCard(modifier = Modifier.fillMaxWidth(), container = palette.container) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = when {
                    state.online -> stringResource(R.string.transmission_online)
                    state.offlineSince == null -> stringResource(R.string.transmission_offline)
                    else -> stringResource(
                        R.string.transmission_offline_since,
                        waitText(Duration.between(state.offlineSince, status.readAt)),
                    )
                },
                color = palette.content,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (state.online) R.string.transmission_online_text else R.string.transmission_offline_text,
                ),
                color = palette.content,
            )
            val first = status.queue.firstOrNull()?.entry
            val next = status.nextAttemptAt
            if (first != null && next != null) {
                Text(
                    text = stringResource(R.string.transmission_next_attempt, dateTimeText(next), first.attempts + 1),
                    color = palette.content,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onRetryNow, enabled = status.queue.isNotEmpty()) {
                AppIconImage(AppIcon.REFRESH, contentDescription = null)
                Text(
                    text = stringResource(R.string.transmission_retry_now),
                    modifier = Modifier.padding(start = Spacing.small),
                )
            }
        }
    }
}

@Composable
private fun Counters(state: TransmissionUiState.Ready) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        val status = state.status
        Counter(status.queue.size, stringResource(R.string.transmission_count_waiting), Modifier.weight(1f))
        Counter(status.rejected.size, stringResource(R.string.transmission_count_rejected), Modifier.weight(1f))
        Counter(status.sentToday, stringResource(R.string.transmission_count_sent_today), Modifier.weight(1f))
    }
}

/** A number and what it counts, read as one by the screen readers. */
@Composable
private fun Counter(count: Int, label: String, modifier: Modifier = Modifier) {
    TonalCard(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$count $label" }) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium.tabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An invoice the OBR refused: which one, the message of the OBR as it gave it, and the way to it. */
@Composable
private fun RejectedCard(document: RejectedDocument, onOpenInvoice: (Long) -> Unit) {
    val invoice = document.invoice.content
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = stringResource(R.string.transmission_document, invoice.number, invoice.customer.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = amountText(invoice.totals.total), color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusBanner(StatusTone.ERROR, stringResource(R.string.transmission_obr_reason, document.message))
            val open = stringResource(R.string.transmission_open_invoice_of, invoice.number)
            OutlinedButton(
                onClick = { onOpenInvoice(document.invoice.id.value) },
                modifier = Modifier.semantics { contentDescription = open },
            ) {
                Text(stringResource(R.string.transmission_open_invoice))
            }
        }
    }
}

/** A document of the queue: its rank, the invoice it sends and what the last attempt left. */
@Composable
private fun QueuedCard(rank: Int, document: QueuedDocument) {
    val entry = document.entry
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            val invoice = document.invoice?.content
            Text(
                text = if (invoice == null) {
                    stringResource(R.string.transmission_queue_rank, rank)
                } else {
                    stringResource(R.string.transmission_queue_document, rank, invoice.number, invoice.customer.name)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (invoice != null) {
                Text(
                    text = stringResource(
                        R.string.transmission_queue_detail,
                        amountText(invoice.totals.total),
                        dateTimeText(invoice.issuedAt),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.uncertain) {
                Text(stringResource(R.string.transmission_uncertain), style = MaterialTheme.typography.bodySmall)
            }
            if (entry.attempts > 0) {
                Text(
                    text = stringResource(R.string.transmission_attempts, entry.attempts, entry.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
