package com.claytonmuhoza.easybilling.invoices

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceDay
import com.claytonmuhoza.easybilling.domain.invoicing.InvoicePeriod
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.sale.label
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.dateText
import com.claytonmuhoza.easybilling.ui.timeText
import java.time.LocalDate

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/** Side of the rounded square that shows the type of a document. */
private val TypeTileSize = 40.dp

/**
 * List of the invoices wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenInvoice called with the identifier of the invoice the user opens.
 * @param viewModel holder of the list state.
 */
@Composable
fun InvoiceListRoute(
    onBack: () -> Unit,
    onOpenInvoice: (Long) -> Unit,
    viewModel: InvoiceListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: an invoice may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    InvoiceListScreen(
        state = state,
        actions = InvoiceListActions(
            onBack = onBack,
            onOpenInvoice = onOpenInvoice,
            onQueryChange = viewModel::onQueryChange,
            onStatusChange = viewModel::onStatusChange,
            onPeriodChange = viewModel::onPeriodChange,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the invoices.
 *
 * @property onBack called when the user goes back.
 * @property onOpenInvoice called with the identifier of the invoice the user opens.
 * @property onQueryChange called with the new content of the search field.
 * @property onStatusChange called with the status to list, or null for every status.
 * @property onPeriodChange called with the period to list.
 * @property onRetry called when the user asks to read the invoices again.
 */
class InvoiceListActions(
    val onBack: () -> Unit = {},
    val onOpenInvoice: (Long) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onStatusChange: (InvoiceStatusKind?) -> Unit = {},
    val onPeriodChange: (InvoicePeriod) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The issued invoices (UC-C08): searched by number or customer, filtered by status and by period,
 * grouped by day with the number and the total of each day.
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun InvoiceListScreen(state: InvoiceListUiState, actions: InvoiceListActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.invoices_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            InvoiceListUiState.Loading -> LoadingState()
            InvoiceListUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoices_load_error),
                onRetry = actions.onRetry,
            )
            is InvoiceListUiState.Ready -> if (state.anyInvoice) {
                InvoiceListContent(state, actions)
            } else {
                EmptyState(
                    title = stringResource(R.string.invoices_empty_title),
                    message = stringResource(R.string.invoices_empty_message),
                )
            }
        }
    }
}

@Composable
private fun InvoiceListContent(state: InvoiceListUiState.Ready, actions: InvoiceListActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SearchField(state.query, actions.onQueryChange, stringResource(R.string.invoices_search))
        StatusFilters(state, actions.onStatusChange)
        PeriodFilters(state.period, actions.onPeriodChange)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            if (state.days.isEmpty()) item { Notice(stringResource(R.string.invoices_no_match)) }
            state.days.forEach { day ->
                item(key = day.date.toEpochDay()) { DayHeader(day, state.today) }
                items(day.invoices, key = { "invoice-${it.id.value}" }) { invoice ->
                    InvoiceCard(invoice, actions.onOpenInvoice)
                }
            }
            if (state.limited) {
                item { Notice(stringResource(R.string.invoices_limit, InvoiceListViewModel.LIMIT), small = true) }
            }
        }
    }
}

/** One chip for every status and one per status, each with how many invoices it would list. */
@Composable
private fun StatusFilters(state: InvoiceListUiState.Ready, onStatusChange: (InvoiceStatusKind?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        FilterChip(
            selected = state.status == null,
            onClick = { onStatusChange(null) },
            label = { Text(countLabel(R.string.invoices_filter_all, state.counts.values.sum())) },
        )
        InvoiceStatusKind.entries.forEach { kind ->
            FilterChip(
                selected = state.status == kind,
                onClick = { onStatusChange(kind) },
                label = { Text(countLabel(STATUS_FILTER_LABELS.getValue(kind), state.counts[kind] ?: 0)) },
            )
        }
    }
}

@Composable
private fun countLabel(label: Int, count: Int): String =
    stringResource(R.string.invoices_filter_count, stringResource(label), count)

@Composable
private fun PeriodFilters(selected: InvoicePeriod, onPeriodChange: (InvoicePeriod) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        InvoicePeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onPeriodChange(period) },
                label = { Text(stringResource(PERIOD_LABELS.getValue(period))) },
            )
        }
    }
}

/** The day, with how many invoices it has and what they add up to. */
@Composable
private fun DayHeader(day: InvoiceDay, today: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (day.date) {
                today -> stringResource(R.string.invoices_period_today)
                today.minusDays(1) -> stringResource(R.string.invoices_yesterday)
                else -> dateText(day.date)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = pluralStringResource(
                R.plurals.invoices_day_summary,
                day.invoices.size,
                day.invoices.size,
                amountText(day.total),
            ),
            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(text: String, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * An invoice: its type, its number and its total on one line, then its customer and its status.
 * A rejected invoice shows the message of the OBR instead of the customer; a cancelled one has its
 * total struck.
 */
@Composable
private fun InvoiceCard(invoice: InvoiceSummary, onOpen: (Long) -> Unit) {
    val kind = invoice.status.kind
    TonalCard(onClick = { onOpen(invoice.id.value) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeTile(invoice.type)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(
                        text = invoice.number,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = amountText(invoice.total),
                        style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.LineThrough.takeIf { kind == InvoiceStatusKind.CANCELLED },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rejection = (invoice.status as? InvoiceStatus.Rejected)?.message
                    Text(
                        text = rejection?.takeIf { it.isNotEmpty() } ?: stringResource(
                            R.string.invoices_row_detail,
                            invoice.customerName,
                            timeText(invoice.issuedAt),
                            invoice.paymentType.label(),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (rejection.isNullOrEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    StatusChip(kind.tone(), kind.label())
                }
            }
        }
    }
}

/** The code of the type of a document, decorative: the number of the document starts with it. */
@Composable
private fun TypeTile(type: DocumentType) {
    Box(
        modifier = Modifier
            .size(TypeTileSize)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = type.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}
