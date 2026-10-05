package com.claytonmuhoza.easybilling.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationRow
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.invoices.InvoiceCard
import com.claytonmuhoza.easybilling.transmission.TransmissionBannerRoute

/** Width from which "New sale" is a card at the top instead of a floating button. */
private const val WIDE_SCREEN_DP = 600

/** Room left under the content of a narrow screen, so the floating button hides nothing. */
private val FloatingButtonRoom = 88.dp

/**
 * Home screen wired to its view model.
 *
 * @param userName name of the logged in user.
 * @param actions where the home screen leads.
 * @param onOpenTransmission called when the user opens the transmission to the OBR.
 * @param viewModel holder of the home state.
 */
@Composable
fun HomeRoute(
    userName: String,
    actions: HomeActions,
    onOpenTransmission: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a sale, an invoice or a setting may have changed.
    LaunchedEffect(Unit) { viewModel.load() }

    HomeScreen(
        userName = userName,
        state = state,
        actions = actions,
        banner = { TransmissionBannerRoute(compact = false, onOpen = onOpenTransmission) },
    )
}

/**
 * What the user can do on the home screen.
 *
 * @property onOpenSettings called when the user opens the settings.
 * @property onOpenSale called when the user opens the cash register.
 * @property onOpenItems called when the user opens the items.
 * @property onOpenCustomers called when the user opens the customers.
 * @property onOpenInvoices called when the user asks for every invoice.
 * @property onOpenInvoice called with the identifier of the invoice the user opens.
 */
class HomeActions(
    val onOpenSettings: () -> Unit = {},
    val onOpenSale: () -> Unit = {},
    val onOpenItems: () -> Unit = {},
    val onOpenCustomers: () -> Unit = {},
    val onOpenInvoices: () -> Unit = {},
    val onOpenInvoice: (Long) -> Unit = {},
)

/**
 * Home screen, the "Register" tab: a welcome, the state of the transmission, the cash register
 * with its sales on hold, the items and the customers, and the latest invoices. The settings are
 * one touch away; what is left to configure before the first invoice is said here.
 *
 * @param userName name of the logged in user.
 * @param state what the screen sums up.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 * @param banner the state of the transmission to the OBR, shown under the welcome.
 */
@Composable
fun HomeScreen(
    userName: String,
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    TitledScreen(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = {
            IconButton(onClick = actions.onOpenSettings) {
                AppIconImage(AppIcon.SETTINGS, contentDescription = stringResource(R.string.settings_title))
            }
        },
    ) {
        FormColumn {
            Text(text = stringResource(R.string.home_welcome, userName), style = MaterialTheme.typography.headlineSmall)
            banner()
            NotReadyNotice(state.readiness, actions.onOpenSettings)
            SaleSection(state.salesStatus, wide, actions.onOpenSale)
            CatalogueSection(state.catalogStatus, state.customersStatus, actions)
            RecentInvoices(state.recentInvoices, actions)
            if (!wide) Spacer(Modifier.height(FloatingButtonRoom))
        }
        if (!wide) NewSaleButton(actions.onOpenSale)
    }
}

/** While the configuration is not complete: why nothing can be sold, and the way to the settings. */
@Composable
private fun NotReadyNotice(status: ReadinessStatus, onOpenSettings: () -> Unit) {
    val known = status as? ReadinessStatus.Known ?: return
    if (!known.readiness.ready) {
        StatusBanner(StatusTone.PENDING, stringResource(R.string.home_readiness_not_ready))
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_open_settings)) }
    }
}

/**
 * The cash register: a card on a wide screen, where the floating button is not shown; on a narrow
 * one, only the sales on hold, when there are some.
 */
@Composable
private fun SaleSection(salesStatus: SalesStatus, wide: Boolean, onOpenSale: () -> Unit) {
    val held = (salesStatus as? SalesStatus.Counted)?.held ?: 0
    if (wide) {
        NavigationRow(
            title = stringResource(R.string.home_new_sale),
            onClick = onOpenSale,
            supporting = salesStatus.text(),
            icon = AppIcon.CART,
            trailing = { AppIconImage(AppIcon.ARROW_FORWARD, contentDescription = null) },
            container = MaterialTheme.colorScheme.primaryContainer,
        )
    } else if (held > 0) {
        NavigationRow(
            title = stringResource(R.string.home_held_sales),
            onClick = onOpenSale,
            supporting = salesStatus.text(),
            icon = AppIcon.PAUSE,
        )
    }
}

/** "New sale", always within reach of the thumb on a narrow screen. */
@Composable
private fun BoxScope.NewSaleButton(onOpenSale: () -> Unit) {
    // The form with its own content: the one with an icon and a text animates its text in.
    ExtendedFloatingActionButton(
        onClick = onOpenSale,
        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.large),
    ) {
        AppIconImage(AppIcon.CART, contentDescription = null)
        Text(stringResource(R.string.home_new_sale), modifier = Modifier.padding(start = Spacing.small))
    }
}

/** The items and the customers, which the sale picks from. */
@Composable
private fun CatalogueSection(catalogStatus: CatalogStatus, customersStatus: CustomersStatus, actions: HomeActions) {
    SectionHeader(stringResource(R.string.home_catalog))
    NavigationRow(
        title = stringResource(R.string.items_title),
        onClick = actions.onOpenItems,
        supporting = catalogStatus.text(),
        icon = AppIcon.ITEMS,
    )
    NavigationRow(
        title = stringResource(R.string.customers_title),
        onClick = actions.onOpenCustomers,
        supporting = customersStatus.text(),
        icon = AppIcon.GROUP,
    )
}

/** The latest invoices, and the way to all of them; nothing while there is no invoice. */
@Composable
private fun RecentInvoices(invoices: List<InvoiceSummary>, actions: HomeActions) {
    if (invoices.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(stringResource(R.string.home_recent_invoices), modifier = Modifier.weight(1f))
        TextButton(onClick = actions.onOpenInvoices) { Text(stringResource(R.string.home_see_all)) }
    }
    invoices.forEach { InvoiceCard(it, actions.onOpenInvoice) }
}
