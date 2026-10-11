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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PricedSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.items.shortLabel
import com.claytonmuhoza.easybilling.identity.ApprovalActions
import com.claytonmuhoza.easybilling.identity.ApprovalDialog
import com.claytonmuhoza.easybilling.transmission.TransmissionBannerRoute
import com.claytonmuhoza.easybilling.ui.MoneyText
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.decimalText

/** Narrowest card of the grid of items: two columns on a phone, more on a wide screen. */
private val ItemCardMinWidth = 150.dp

/** From this width, the catalogue and the sale are shown side by side (tablet, cash terminal). */
private val TwoPaneMinWidth = 720.dp

/**
 * Cash register wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onChooseCustomer called when the user asks to choose the customer of the sale.
 * @param onOpenHeld called when the user opens the sales on hold.
 * @param onIssued called with the identifier of the invoice once the sale is issued.
 * @param onOpenTransmission called when the user opens the transmission to the OBR.
 * @param onSwitchUser called when the user hands the register over to another user.
 * @param viewModel holder of the sale state.
 */
@Composable
fun SaleRoute(
    onBack: () -> Unit,
    onChooseCustomer: () -> Unit,
    onOpenHeld: () -> Unit,
    onIssued: (Long) -> Unit,
    onOpenTransmission: () -> Unit,
    onSwitchUser: () -> Unit,
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
            onSwitchUser = onSwitchUser,
        ),
        approvalActions = ApprovalActions(
            onChoose = viewModel.approval::choose,
            onPinChange = viewModel.approval::onPinChange,
            onSubmit = viewModel.approval::submit,
            onCancel = viewModel.approval::cancel,
        ),
        banner = {
            TransmissionBannerRoute(
                compact = true,
                onOpen = onOpenTransmission,
                modifier = Modifier.padding(horizontal = Spacing.large).padding(bottom = Spacing.small),
            )
        },
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
 * @property onSwitchUser called when the user hands the register over to another user.
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
    val onSwitchUser: () -> Unit = {},
)

/**
 * The cash register (UC-C01 steps 1 to 5, UC-C02, UC-C03): the items to add and the sale. A wide
 * screen shows both side by side; a narrow one shows one at a time.
 *
 * @param state the sale state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param banner the state of the transmission to the OBR, shown above the sale when it needs attention.
 * @param approvalActions what the user can do on the approval of a discount beyond the cap.
 */
@Composable
fun SaleScreen(
    state: SaleUiState,
    actions: SaleActions,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    approvalActions: ApprovalActions = ApprovalActions(),
) {
    val ready = state as? SaleUiState.Ready
    val held = ready?.heldCount ?: 0
    TitledScreen(
        title = stringResource(R.string.sale_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = {
            if (held > 0) {
                TextAction(label = stringResource(R.string.sale_held_action, held), onClick = actions.onOpenHeld)
            }
            IconButton(onClick = actions.onSwitchUser) {
                AppIconImage(AppIcon.SWITCH_ACCOUNT, contentDescription = stringResource(R.string.switch_user))
            }
        },
    ) {
        ready?.approval?.let { approval ->
            ApprovalDialog(
                message = stringResource(
                    R.string.approval_discount_message,
                    decimalText(approval.rate),
                    decimalText(approval.cap),
                ),
                state = approval.dialog,
                actions = approvalActions,
                busy = ready.busy,
            )
        }
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
            is SaleUiState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                banner()
                state.replaces?.let { number ->
                    StatusBanner(
                        tone = StatusTone.NEUTRAL,
                        text = stringResource(R.string.sale_replaces, number),
                        modifier = Modifier.padding(horizontal = Spacing.large).padding(bottom = Spacing.small),
                    )
                }
                SalePanes(state, actions)
            }
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
        SearchField(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            label = stringResource(R.string.sale_search),
            onSubmit = actions.onSearchSubmit,
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(ItemCardMinWidth),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.small),
        ) {
            items(state.results, key = { it.id.value }) { item ->
                val inSale = state.sale.draft.lines.firstOrNull { it.itemId == item.id }?.quantity
                CatalogueItem(item, inSale?.let { decimalText(it.value) }, actions.onAddItem)
            }
        }
        if (narrow) CartBar(state.sale, onCheckout = { actions.onShowPane(SalePane.CART) })
    }
}

/**
 * What the sale holds while the catalogue is shown, and the way to the sale: how many items, the
 * total, and the button that goes on to the payment.
 */
@Composable
private fun CartBar(sale: PricedSale, onCheckout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.medium),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = Spacing.extraSmall,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            AppIconImage(AppIcon.CART, contentDescription = null)
            Text(
                text = pluralStringResource(
                    R.plurals.held_sale_summary,
                    sale.lines.size,
                    sale.lines.size,
                    amountText(sale.totals.total),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(onClick = onCheckout) {
                Text(stringResource(R.string.sale_checkout))
                AppIconImage(AppIcon.ARROW_FORWARD, contentDescription = null)
            }
        }
    }
}

/** An item to add with one touch. [inSale] is the quantity already in the sale, or null. */
@Composable
private fun CatalogueItem(item: Item, inSale: String?, onAdd: (ItemId) -> Unit) {
    val selected = inSale != null
    TonalCard(
        onClick = { onAdd(item.id) },
        modifier = Modifier.fillMaxWidth(),
        container = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = item.details.designation,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.code} · ${item.details.priceMode.shortLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MoneyText(
                    money = item.details.price,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (inSale == null) {
                    AppIconImage(AppIcon.ADD, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        text = stringResource(R.string.sale_in_sale, inSale),
                        style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
