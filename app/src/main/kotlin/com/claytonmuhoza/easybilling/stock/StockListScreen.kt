package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.allowed
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.stock.StockLine
import com.claytonmuhoza.easybilling.domain.stock.StockSummary
import com.claytonmuhoza.easybilling.ui.amountText

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/**
 * List of the items kept in stock wired to its view model.
 *
 * @param onOpenItem called with the identifier of the item the user picks.
 * @param onOpenInventory called when the user opens the inventory.
 * @param viewModel holder of the list state.
 */
@Composable
fun StockListRoute(
    onOpenItem: (Long) -> Unit,
    onOpenInventory: () -> Unit,
    viewModel: StockListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a movement may have been recorded on another screen.
    LaunchedEffect(Unit) { viewModel.load() }

    StockListScreen(
        state = state,
        actions = StockListActions(
            onOpenItem = onOpenItem,
            onOpenInventory = onOpenInventory,
            onQueryChange = viewModel::onQueryChange,
            onToReorderOnlyChange = viewModel::onToReorderOnlyChange,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the items kept in stock.
 *
 * @property onOpenItem called with the identifier of the item the user picks.
 * @property onOpenInventory called when the user opens the inventory.
 * @property onQueryChange called with the new content of the search field.
 * @property onToReorderOnlyChange called when the user shows only the items to reorder, or all of them.
 * @property onRetry called when the user asks to read the stock again.
 */
class StockListActions(
    val onOpenItem: (Long) -> Unit = {},
    val onOpenInventory: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onToReorderOnlyChange: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The items kept in stock with their stock, its value, the items to reorder and their search
 * (UC-F10), and the way to the inventory (UC-F08).
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun StockListScreen(state: StockListUiState, actions: StockListActions, modifier: Modifier = Modifier) {
    val counts = allowed(Permission.RECORD_STOCK)
    TitledScreen(
        title = stringResource(R.string.stock_title),
        modifier = modifier,
        actions = {
            if (counts) {
                TextAction(label = stringResource(R.string.inventory_title), onClick = actions.onOpenInventory)
            }
        },
    ) {
        when (state) {
            StockListUiState.Loading -> LoadingState()
            StockListUiState.Failed -> ErrorState(
                message = stringResource(R.string.stock_load_error),
                onRetry = actions.onRetry,
            )
            is StockListUiState.Ready -> if (state.query.isBlank() && state.lines.isEmpty() && !state.toReorderOnly) {
                EmptyState(
                    title = stringResource(R.string.stock_empty_title),
                    message = stringResource(R.string.stock_empty_message),
                )
            } else {
                StockListContent(state, actions)
            }
        }
    }
}

@Composable
private fun StockListContent(state: StockListUiState.Ready, actions: StockListActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SummaryCard(state.summary)
        SearchField(state.query, actions.onQueryChange, stringResource(R.string.items_search))
        if (state.summary.toReorder > 0 || state.toReorderOnly) {
            SwitchRow(
                label = stringResource(R.string.stock_to_reorder_only, state.summary.toReorder),
                checked = state.toReorderOnly,
                onCheckedChange = actions.onToReorderOnlyChange,
            )
        }
        if (state.lines.isEmpty()) {
            Text(
                text = stringResource(R.string.stock_no_match),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            items(state.lines, key = { it.item.id.value }) { line -> StockLineCard(line, actions.onOpenItem) }
        }
    }
}

/** What the whole stock is worth, and how many items are left out of that value. */
@Composable
private fun SummaryCard(summary: StockSummary) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(
                text = stringResource(R.string.stock_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = amountText(summary.value),
                style = MaterialTheme.typography.titleLarge.tabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
            if (summary.unvalued > 0) {
                Text(
                    text = pluralStringResource(R.plurals.stock_unvalued, summary.unvalued, summary.unvalued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StockLineCard(line: StockLine, onOpen: (Long) -> Unit) {
    TonalCard(onClick = { onOpen(line.item.id.value) }, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            leadingContent = { IconTile(AppIcon.INVENTORY) },
            headlineContent = { Text(line.item.details.designation) },
            supportingContent = {
                Column {
                    Text(line.item.code)
                    line.value?.let { Text(stringResource(R.string.stock_line_value, amountText(it))) }
                    if (line.toReorder) StatusChip(StatusTone.PENDING, stringResource(R.string.stock_threshold_reached))
                }
            },
            trailingContent = {
                Text(
                    text = quantityText(line.quantity, line.item.details.unit),
                    style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    // An empty stock stands out; its "0" says it without the colour.
                    color = if (line.quantity.signum() > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            },
        )
    }
}
