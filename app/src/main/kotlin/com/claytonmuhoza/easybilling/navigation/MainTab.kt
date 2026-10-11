package com.claytonmuhoza.easybilling.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.LocalPermissions
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationEntry
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationFrame
import com.claytonmuhoza.easybilling.domain.identity.Permission

/**
 * The main screens, between which the navigation switches. Reports join them with their own step.
 *
 * @property icon the icon of the tab.
 * @property label the name of the tab.
 * @property destination the screen the tab shows.
 * @property requires what the user must be allowed for the tab to be shown, or null.
 */
enum class MainTab(val icon: AppIcon, @StringRes val label: Int, val destination: Any, val requires: Permission?) {
    /** The home screen: the cash register and what surrounds it. */
    REGISTER(AppIcon.STORE, R.string.nav_register, HomeDestination, null),

    /** The issued invoices. */
    INVOICES(AppIcon.INVOICE, R.string.invoices_title, InvoicesDestination, Permission.VIEW_INVOICES),

    /** The stock of the items kept in stock. */
    STOCK(AppIcon.INVENTORY, R.string.stock_title, StockDestination, Permission.VIEW_STOCK),
}

/**
 * A main screen inside the navigation between the main screens: a bar on a phone, a rail on a
 * tablet. Only the tabs the user is allowed are shown; with a single one, there is no navigation.
 *
 * @param selected the tab whose screen is shown.
 * @param onSelect called with the tab the user picks.
 * @param modifier modifier applied to the whole frame.
 * @param content the screen of [selected].
 */
@Composable
fun MainTabs(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val permissions = LocalPermissions.current
    val tabs = MainTab.entries.filter { tab -> tab.requires == null || tab.requires in permissions }
    if (tabs.size < 2) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    NavigationFrame(
        entries = tabs.map { NavigationEntry(it.icon, stringResource(it.label)) },
        selected = tabs.indexOf(selected),
        onSelect = { onSelect(tabs[it]) },
        modifier = modifier,
        content = content,
    )
}
