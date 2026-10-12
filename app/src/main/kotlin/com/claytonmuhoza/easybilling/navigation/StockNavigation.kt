package com.claytonmuhoza.easybilling.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.stock.DmcRoute
import com.claytonmuhoza.easybilling.stock.ImportEntryRoute
import com.claytonmuhoza.easybilling.stock.ImportsRoute
import com.claytonmuhoza.easybilling.stock.InventoryRoute
import com.claytonmuhoza.easybilling.stock.StockItemRoute
import com.claytonmuhoza.easybilling.stock.StockListRoute
import com.claytonmuhoza.easybilling.stock.StockMovementRoute

/** The "Stock" tab and the ways out of it: an item, the inventory, the imported goods. */
@Composable
internal fun StockTab(navController: NavHostController) {
    StockListRoute(
        onOpenItem = { id -> navController.navigate(StockItemDestination(id)) },
        onOpenInventory = { navController.navigate(InventoryDestination) },
        onOpenImports = { navController.navigate(ImportsDestination) },
    )
}

/**
 * The stock of an item, its movements, the entry of a movement, the inventory and the imported goods
 * (UC-F01 to F10).
 */
internal fun NavGraphBuilder.stockScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<StockItemDestination> {
        LoggedIn(access, navController, Permission.VIEW_STOCK) {
            StockItemRoute(
                onBack = { navController.popBackStack() },
                onNewMovement = { itemId, exit -> navController.navigate(StockMovementDestination(itemId, exit)) },
            )
        }
    }
    composable<InventoryDestination> {
        LoggedIn(access, navController, Permission.RECORD_STOCK) {
            InventoryRoute(onBack = { navController.popBackStack() })
        }
    }
    composable<StockMovementDestination> {
        LoggedIn(access, navController, Permission.RECORD_STOCK) {
            StockMovementRoute(onClose = { navController.popBackStack() })
        }
    }
    importScreens(access, navController)
}

/** The customs declarations and the entry of imported goods (UC-F03, F04). */
private fun NavGraphBuilder.importScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<ImportsDestination> {
        LoggedIn(access, navController, Permission.RECORD_STOCK) {
            ImportsRoute(
                onBack = { navController.popBackStack() },
                onOpen = { reference -> navController.navigate(DmcDestination(reference)) },
            )
        }
    }
    composable<DmcDestination> {
        LoggedIn(access, navController, Permission.RECORD_STOCK) {
            DmcRoute(
                onBack = { navController.popBackStack() },
                onEnter = { reference, line -> navController.navigate(ImportEntryDestination(reference, line)) },
            )
        }
    }
    composable<ImportEntryDestination> {
        LoggedIn(access, navController, Permission.RECORD_STOCK) {
            ImportEntryRoute(
                onClose = { navController.popBackStack() },
                onNewItem = { navController.navigate(ItemEditorDestination()) },
            )
        }
    }
}
