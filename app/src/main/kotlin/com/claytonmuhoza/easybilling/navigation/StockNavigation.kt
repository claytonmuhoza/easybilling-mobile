package com.claytonmuhoza.easybilling.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.stock.InventoryRoute
import com.claytonmuhoza.easybilling.stock.StockItemRoute
import com.claytonmuhoza.easybilling.stock.StockMovementRoute

/** The stock of an item, its movements, the entry of a movement and the inventory (UC-F01 to F10). */
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
}
