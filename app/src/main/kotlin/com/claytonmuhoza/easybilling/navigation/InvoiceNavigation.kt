package com.claytonmuhoza.easybilling.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.invoices.CancelInvoiceRoute
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailRoute

/**
 * The detail of an invoice and its cancellation (UC-C08, UC-E01, E02). Anyone who sees the
 * invoices may ask for a cancellation: a user who may not correct an invoice needs the PIN of a
 * manager.
 */
internal fun NavGraphBuilder.invoiceScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<InvoiceDestination> { entry ->
        val id = entry.toRoute<InvoiceDestination>().id
        LoggedIn(access, navController, Permission.VIEW_INVOICES) {
            InvoiceDetailRoute(
                onBack = { navController.popBackStack() },
                onOpenSale = { navController.navigate(SaleDestination) },
                onCancelInvoice = { navController.navigate(CancelInvoiceDestination(id)) },
            )
        }
    }
    composable<CancelInvoiceDestination> {
        LoggedIn(access, navController, Permission.VIEW_INVOICES) {
            CancelInvoiceRoute(
                onClose = { navController.popBackStack() },
                onReplaced = {
                    navController.navigate(SaleDestination) {
                        popUpTo<CancelInvoiceDestination> { inclusive = true }
                    }
                },
            )
        }
    }
}
