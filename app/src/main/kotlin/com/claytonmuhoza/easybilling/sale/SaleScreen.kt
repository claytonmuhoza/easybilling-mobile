package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.items.summary
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.decimalText

/** From this width, the catalogue and the sale are shown side by side (tablet, cash terminal). */
private val TwoPaneMinWidth = 720.dp

/**
 * Cash register wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onChooseCustomer called when the user asks to choose the customer of the sale.
 * @param onOpenHeld called when the user opens the sales on hold.
 * @param onIssued called with the identifier of the invoice once the sale is issued.
 * @param viewModel holder of the sale state.
 */
@Composable
fun SaleRoute(
    onBack: () -> Unit,
    onChooseCustomer: () -> Unit,
    onOpenHeld: () -> Unit,
    onIssued: (Long) -> Unit,
    viewModel: SaleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the customer or the sales on hold may have changed.
    LaunchedEffect(Unit) { viewModel.load() }
    (state as? SaleUiState.Issued)?.let { issued -> LaunchedEffect(issued) { onIssued(issued.invoiceId) } }

    SaleScreen(
        state = state,
        actions = SaleActions(
            onBack = onBack,
            onChooseCustomer = onChooseCustomer,
            onOpenHeld = onOpenHeld,
            onRetry = viewModel::load,
            onQueryChange = viewModel.catalogue::onQueryChange,
            onSearchSubmit = viewModel.catalogue::onSearchSubmit,
            onAddItem = viewModel.catalogue::addItem,
            onRemoveOne = viewModel.catalogue::removeOne,
            onOpenLine = viewModel.lines::open,
            onCloseLine = viewModel.lines::close,
            onLineFormChange = viewModel.lines::onFormChange,
            onApplyLine = viewModel.lines::apply,
            onRemoveLine = viewModel.lines::remove,
            onPaymentTypeChange = viewModel::setPaymentType,
            onShowPane = viewModel::showPane,
            onHold = viewModel::hold,
            onIssue = viewModel::issue,
        ),
    )
}

/**
 * What the user can do on the cash register.
 *
 * @property onBack called when the user goes back.
 * @property onChooseCustomer called when the user asks to choose the customer.
 * @property onOpenHeld called when the user opens the sales on hold.
 * @property onRetry called when the user asks to read the sale again.
 * @property onQueryChange called with the new content of the search field.
 * @property onSearchSubmit called when the search is submitted, by the keyboard or a barcode reader.
 * @property onAddItem called with the item to add.
 * @property onRemoveOne called with the item whose line loses one unit.
 * @property onOpenLine called with the item whose line is opened to be modified.
 * @property onCloseLine called when the line being modified is closed unchanged.
 * @property onLineFormChange called with the change of one field of the line being modified.
 * @property onApplyLine called when the user applies what was typed for the line.
 * @property onRemoveLine called with the item whose line is taken out.
 * @property onPaymentTypeChange called with the way to pay.
 * @property onShowPane called with the part a narrow screen must show.
 * @property onHold called when the user puts the sale on hold.
 * @property onIssue called when the user validates the sale.
 */
class SaleActions(
    val onBack: () -> Unit = {},
    val onChooseCustomer: () -> Unit = {},
    val onOpenHeld: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSearchSubmit: () -> Unit = {},
    val onAddItem: (ItemId) -> Unit = {},
    val onRemoveOne: (ItemId) -> Unit = {},
    val onOpenLine: (ItemId) -> Unit = {},
    val onCloseLine: () -> Unit = {},
    val onLineFormChange: (SaleLineForm.() -> SaleLineForm) -> Unit = {},
    val onApplyLine: () -> Unit = {},
    val onRemoveLine: (ItemId) -> Unit = {},
    val onPaymentTypeChange: (PaymentType) -> Unit = {},
    val onShowPane: (SalePane) -> Unit = {},
    val onHold: () -> Unit = {},
    val onIssue: () -> Unit = {},
)

/**
 * The cash register (UC-C01 steps 1 to 5, UC-C02, UC-C03): the items to add and the sale. A wide
 * screen shows both side by side; a narrow one shows one at a time.
 *
 * @param state the sale state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SaleScreen(state: SaleUiState, actions: SaleActions, modifier: Modifier = Modifier) {
    val held = (state as? SaleUiState.Ready)?.heldCount ?: 0
    TitledScreen(
        title = stringResource(R.string.sale_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = {
            if (held > 0) {
                TextAction(label = stringResource(R.string.sale_held_action, held), onClick = actions.onOpenHeld)
            }
        },
    ) {
        when (state) {
            SaleUiState.Loading, is SaleUiState.Issued -> LoadingState()
            SaleUiState.Failed -> ErrorState(
                message = stringResource(R.string.sale_load_error),
                onRetry = actions.onRetry,
            )
            is SaleUiState.NotReady -> ErrorState(
                message = stringResource(R.string.sale_not_ready_message),
                title = stringResource(R.string.sale_not_ready_title),
            )
            is SaleUiState.Ready -> SalePanes(state, actions)
        }
    }
}

@Composable
private fun SalePanes(state: SaleUiState.Ready, actions: SaleActions) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneMinWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                CataloguePane(state, actions, narrow = false, modifier = Modifier.weight(1f).fillMaxHeight())
                SaleCart(state, actions, narrow = false, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else if (state.pane == SalePane.CATALOGUE) {
            CataloguePane(state, actions, narrow = true, modifier = Modifier.fillMaxSize())
        } else {
            SaleCart(state, actions, narrow = true, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The items to add: a search, the items found, and on a narrow screen the way to the sale. */
@Composable
private fun CataloguePane(
    state: SaleUiState.Ready,
    actions: SaleActions,
    narrow: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.sale_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSearchSubmit() }),
            singleLine = true,
        )
        if (state.results.isEmpty()) {
            Text(
                text = stringResource(
                    if (state.query.isBlank()) R.string.sale_catalogue_empty else R.string.items_no_match,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.small),
        ) {
            items(state.results, key = { it.id.value }) { item ->
                val inSale = state.sale.draft.lines.firstOrNull { it.itemId == item.id }?.quantity
                CatalogueItem(item, inSale?.let { decimalText(it.value) }, actions.onAddItem)
            }
        }
        if (narrow) {
            Button(
                onClick = { actions.onShowPane(SalePane.CART) },
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
            ) {
                Text(stringResource(R.string.sale_view_cart, amountText(state.sale.totals.total)))
            }
        }
    }
}

/** An item to add with one touch. [inSale] is the quantity already in the sale, or null. */
@Composable
private fun CatalogueItem(item: Item, inSale: String?, onAdd: (ItemId) -> Unit) {
    OutlinedCard(onClick = { onAdd(item.id) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(item.details.designation) },
            supportingContent = { Text(item.summary()) },
            trailingContent = inSale?.let {
                {
                    Text(
                        text = stringResource(R.string.sale_in_sale, it),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
}
