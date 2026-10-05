package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Width from which the navigation is a rail at the start instead of a bar at the bottom. */
private val RailMinWidth = 600.dp

/**
 * One of the main screens, as the navigation shows it.
 *
 * @property icon the icon of the screen.
 * @property label the name of the screen, always shown under the icon.
 */
data class NavigationEntry(val icon: AppIcon, val label: String)

/** Tags of the two forms of the navigation, for the tests. */
object NavigationFrameTags {
    /** The bar at the bottom of a narrow screen. */
    const val BAR = "navigation-bar"

    /** The rail at the start of a wide screen. */
    const val RAIL = "navigation-rail"
}

/**
 * The navigation between the main screens around one of them: a bar at the bottom of a phone, a
 * rail at the start of a tablet. Each entry has an icon and a name, and says whether it is the
 * screen shown.
 *
 * @param entries the main screens, in order.
 * @param selected the index of the screen shown.
 * @param onSelect called with the index of the entry the user picks.
 * @param modifier modifier applied to the whole frame.
 * @param content the screen shown.
 */
@Composable
fun NavigationFrame(
    entries: List<NavigationEntry>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= RailMinWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.testTag(NavigationFrameTags.RAIL)) {
                    entries.forEachIndexed { index, entry ->
                        NavigationRailItem(
                            selected = index == selected,
                            onClick = { onSelect(index) },
                            icon = { AppIconImage(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                NavigationBar(modifier = Modifier.testTag(NavigationFrameTags.BAR)) {
                    entries.forEachIndexed { index, entry ->
                        NavigationBarItem(
                            selected = index == selected,
                            onClick = { onSelect(index) },
                            icon = { AppIconImage(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        }
    }
}
