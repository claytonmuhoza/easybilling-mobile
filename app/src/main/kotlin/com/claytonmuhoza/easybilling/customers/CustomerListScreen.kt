package com.claytonmuhoza.easybilling.customers

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
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.customer.Customer

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/**
 * List of the customers wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenCustomer called with the identifier of the customer the user picks.
 * @param onNewCustomer called when the user asks for a new customer.
 * @param viewModel holder of the list state.
 */
@Composable
fun CustomerListRoute(
    onBack: () -> Unit,
    onOpenCustomer: (Long) -> Unit,
    onNewCustomer: () -> Unit,
    viewModel: CustomerListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a customer may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    CustomerListScreen(
        state = state,
        actions = CustomerListActions(
            onBack = onBack,
            onOpenCustomer = onOpenCustomer,
            onNewCustomer = onNewCustomer,
            onQueryChange = viewModel::onQueryChange,
            onShowArchivedChange = viewModel::onShowArchivedChange,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the customers.
 *
 * @property onBack called when the user goes back.
 * @property onOpenCustomer called with the identifier of the customer the user picks.
 * @property onNewCustomer called when the user asks for a new customer.
 * @property onQueryChange called with the new content of the search field.
 * @property onShowArchivedChange called when the user shows or hides the archived customers.
 * @property onRetry called when the user asks to read the customers again.
 */
class CustomerListActions(
    val onBack: () -> Unit = {},
    val onOpenCustomer: (Long) -> Unit = {},
    val onNewCustomer: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onShowArchivedChange: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The customers, with their search (UC-B07). The counter customer is always first: with no other
 * customer yet, it stands with a sentence saying what the list is for.
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun CustomerListScreen(state: CustomerListUiState, actions: CustomerListActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.customers_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.customers_new), onClick = actions.onNewCustomer) },
    ) {
        when (state) {
            CustomerListUiState.Loading -> LoadingState()
            CustomerListUiState.Failed -> ErrorState(
                message = stringResource(R.string.customers_load_error),
                onRetry = actions.onRetry,
            )
            is CustomerListUiState.Ready -> CustomerListContent(state, actions)
        }
    }
}

@Composable
private fun CustomerListContent(state: CustomerListUiState.Ready, actions: CustomerListActions) {
    val none = state.activeCount + state.archivedCount == 0
    Column(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (!none) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.customers_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                singleLine = true,
            )
            SwitchRow(
                label = stringResource(R.string.customers_show_archived),
                checked = state.showArchived,
                onCheckedChange = actions.onShowArchivedChange,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            if (!state.showArchived && state.query.isBlank()) {
                item(key = state.counter.id.value) { CounterCard(state.counter, actions.onOpenCustomer) }
            }
            emptyMessage(state, none)?.let { message -> item { Notice(stringResource(message)) } }
            items(state.customers, key = { it.id.value }) { customer ->
                CustomerCard(customer, actions.onOpenCustomer)
            }
            if (state.customers.size == CustomerListViewModel.LIMIT) {
                item { Notice(stringResource(R.string.customers_limit, CustomerListViewModel.LIMIT), small = true) }
            }
        }
    }
}

/** @return the sentence shown when no customer is listed, or null. */
private fun emptyMessage(state: CustomerListUiState.Ready, none: Boolean): Int? = when {
    state.customers.isNotEmpty() -> null
    none -> R.string.customers_empty_message
    state.query.isNotBlank() -> R.string.customers_no_match
    state.showArchived -> R.string.customers_no_archived
    else -> R.string.customers_all_archived
}

@Composable
private fun Notice(text: String, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CounterCard(counter: Customer, onOpen: (Long) -> Unit) {
    OutlinedCard(onClick = { onOpen(counter.id.value) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(counter.details.name) },
            supportingContent = { Text(stringResource(R.string.customers_counter_label)) },
        )
    }
}

@Composable
private fun CustomerCard(customer: Customer, onOpen: (Long) -> Unit) {
    OutlinedCard(onClick = { onOpen(customer.id.value) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(customer.details.name) },
            supportingContent = {
                Column {
                    Text(customer.summary())
                    if (customer.details.phone.isNotEmpty()) Text(customer.details.phone)
                }
            },
        )
    }
}
