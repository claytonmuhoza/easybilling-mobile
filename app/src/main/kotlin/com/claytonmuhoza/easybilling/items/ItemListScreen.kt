package com.claytonmuhoza.easybilling.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StateAction
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/**
 * List of the items wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenItem called with the identifier of the item the user picks.
 * @param onNewItem called when the user asks for a new item.
 * @param viewModel holder of the list state.
 */
@Composable
fun ItemListRoute(
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onNewItem: () -> Unit,
    viewModel: ItemListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: an item may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    ItemListScreen(
        state = state,
        actions = ItemListActions(
            onBack = onBack,
            onOpenItem = onOpenItem,
            onNewItem = onNewItem,
            onQueryChange = viewModel::onQueryChange,
            onShowArchivedChange = viewModel::onShowArchivedChange,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the items.
 *
 * @property onBack called when the user goes back.
 * @property onOpenItem called with the identifier of the item the user picks.
 * @property onNewItem called when the user asks for a new item.
 * @property onQueryChange called with the new content of the search field.
 * @property onShowArchivedChange called when the user shows or hides the archived items.
 * @property onRetry called when the user asks to read the items again.
 */
class ItemListActions(
    val onBack: () -> Unit = {},
    val onOpenItem: (Long) -> Unit = {},
    val onNewItem: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onShowArchivedChange: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The items of the catalogue, with their search (UC-B07).
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ItemListScreen(state: ItemListUiState, actions: ItemListActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.items_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.items_new), onClick = actions.onNewItem) },
    ) {
        when (state) {
            ItemListUiState.Loading -> LoadingState()
            ItemListUiState.Failed -> ErrorState(
                message = stringResource(R.string.items_load_error),
                onRetry = actions.onRetry,
            )
            is ItemListUiState.Ready -> if (state.activeCount + state.archivedCount == 0) {
                EmptyState(
                    title = stringResource(R.string.items_empty_title),
                    message = stringResource(R.string.items_empty_message),
                    action = StateAction(stringResource(R.string.items_new), actions.onNewItem),
                )
            } else {
                ItemListContent(state, actions)
            }
        }
    }
}

@Composable
private fun ItemListContent(state: ItemListUiState.Ready, actions: ItemListActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.items_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            singleLine = true,
        )
        SwitchRow(
            label = stringResource(R.string.items_show_archived),
            checked = state.showArchived,
            onCheckedChange = actions.onShowArchivedChange,
        )
        if (state.items.isEmpty()) {
            Text(
                text = stringResource(
                    when {
                        state.query.isNotBlank() -> R.string.items_no_match
                        state.showArchived -> R.string.items_no_archived
                        else -> R.string.items_all_archived
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            items(state.items, key = { it.id.value }) { item ->
                ItemCard(item, state.registrations, actions.onOpenItem)
            }
            if (state.items.size == ItemListViewModel.LIMIT) {
                item {
                    Text(
                        text = stringResource(R.string.items_limit, ItemListViewModel.LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, registrations: TaxRegistrations, onOpen: (Long) -> Unit) {
    OutlinedCard(onClick = { onOpen(item.id.value) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(item.details.designation) },
            supportingContent = {
                Column {
                    Text(item.summary())
                    Text(listOfNotNull(stockLabel(item.details.stockTracked), item.details.barcode).joinToString(" · "))
                    if (item.needsVat(registrations)) {
                        Text(stringResource(R.string.items_vat_to_choose), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}
