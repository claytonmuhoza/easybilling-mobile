package com.claytonmuhoza.easybilling.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationEntry
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationFrame

/**
 * The main screens, between which the navigation switches. Stock and Reports join them with
 * their own steps.
 *
 * @property icon the icon of the tab.
 * @property label the name of the tab.
 * @property destination the screen the tab shows.
 */
enum class MainTab(val icon: AppIcon, @StringRes val label: Int, val destination: Any) {
    /** The home screen: the cash register and what surrounds it. */
    REGISTER(AppIcon.STORE, R.string.nav_register, HomeDestination),

    /** The issued invoices. */
    INVOICES(AppIcon.INVOICE, R.string.invoices_title, InvoicesDestination),
}

/**
 * A main screen inside the navigation between the main screens: a bar on a phone, a rail on a
 * tablet.
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
    NavigationFrame(
        entries = MainTab.entries.map { NavigationEntry(it.icon, stringResource(it.label)) },
        selected = selected.ordinal,
        onSelect = { onSelect(MainTab.entries[it]) },
        modifier = modifier,
        content = content,
    )
}
