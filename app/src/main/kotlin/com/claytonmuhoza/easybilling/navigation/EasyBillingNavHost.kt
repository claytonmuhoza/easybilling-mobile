package com.claytonmuhoza.easybilling.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.customers.CustomerEditorRoute
import com.claytonmuhoza.easybilling.customers.CustomerListRoute
import com.claytonmuhoza.easybilling.domain.identity.Session
import com.claytonmuhoza.easybilling.ebms.EbmsConnectionRoute
import com.claytonmuhoza.easybilling.ebms.ExchangeLogRoute
import com.claytonmuhoza.easybilling.ebms.ExchangeRoute
import com.claytonmuhoza.easybilling.home.HomeActions
import com.claytonmuhoza.easybilling.home.HomeRoute
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailRoute
import com.claytonmuhoza.easybilling.invoices.InvoiceListRoute
import com.claytonmuhoza.easybilling.items.ItemEditorRoute
import com.claytonmuhoza.easybilling.items.ItemListRoute
import com.claytonmuhoza.easybilling.login.LoginRoute
import com.claytonmuhoza.easybilling.numbering.NumberingRoute
import com.claytonmuhoza.easybilling.recovery.ResetPinRoute
import com.claytonmuhoza.easybilling.sale.HeldSalesRoute
import com.claytonmuhoza.easybilling.sale.InvoiceIssuedRoute
import com.claytonmuhoza.easybilling.sale.SaleCustomerRoute
import com.claytonmuhoza.easybilling.sale.SaleRoute
import com.claytonmuhoza.easybilling.settings.SettingsActions
import com.claytonmuhoza.easybilling.settings.SettingsRoute
import com.claytonmuhoza.easybilling.setup.SetupRoute
import com.claytonmuhoza.easybilling.taxes.LinePreviewRoute
import com.claytonmuhoza.easybilling.taxes.TaxEditorRoute
import com.claytonmuhoza.easybilling.taxes.TaxListRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileHistoryRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileVersionRoute
import com.claytonmuhoza.easybilling.transmission.TransmissionRoute

/** The destination a section of the application starts on. */
private fun AccessState.Ready.entryDestination(): Any = when (this) {
    AccessState.SetupRequired -> SetupDestination
    AccessState.LoggedOut -> LoginDestination
    is AccessState.LoggedIn -> HomeDestination
}

/**
 * Navigation graph of the application.
 *
 * When the access state moves to another section (assistant, login, application), the whole back
 * stack is replaced by the entry of that section: no screen of the previous section can be
 * reached with the back button. The screens of the application are only composed with a session.
 *
 * @param access the section the user is allowed to see.
 * @param onSetupFinished called when the first launch assistant is over.
 * @param onLogOut called when the user asks to log out.
 * @param modifier modifier applied to the navigation host.
 * @param navController controller driving the navigation; replaceable in tests.
 */
@Composable
fun EasyBillingNavHost(
    access: AccessState.Ready,
    onSetupFinished: () -> Unit,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val entry = access.entryDestination()
    val startDestination = remember { entry }
    var shownSection by rememberSaveable { mutableStateOf(entry::class.qualifiedName) }

    LaunchedEffect(entry) {
        if (shownSection != entry::class.qualifiedName) {
            navController.navigate(entry) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            shownSection = entry::class.qualifiedName
        }
    }

    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable<SetupDestination> {
            SetupRoute(onFinished = onSetupFinished)
        }
        composable<LoginDestination> {
            LoginRoute(onForgotPin = { navController.navigate(ResetPinDestination) })
        }
        composable<ResetPinDestination> {
            ResetPinRoute(onClose = { navController.popBackStack() })
        }
        mainTabs(access, navController)
        composable<SettingsDestination> {
            LoggedIn(access) { session ->
                SettingsRoute(
                    userName = session.userName,
                    actions = SettingsActions(
                        onBack = { navController.popBackStack() },
                        onLogOut = onLogOut,
                        onOpenTaxProfile = { navController.navigate(TaxProfileDestination) },
                        onOpenEbmsConnection = { navController.navigate(EbmsConnectionDestination) },
                        onOpenTaxes = { navController.navigate(TaxesDestination) },
                        onOpenNumbering = { navController.navigate(NumberingDestination) },
                    ),
                )
            }
        }
        taxProfileScreens(access, navController)
        ebmsScreens(access, navController)
        taxScreens(access, navController)
        itemScreens(access, navController)
        customerScreens(access, navController)
        saleScreens(access, navController)
        composable<InvoiceDestination> {
            LoggedIn(access) {
                InvoiceDetailRoute(
                    onBack = { navController.popBackStack() },
                    onOpenSale = { navController.navigate(SaleDestination) },
                )
            }
        }
        composable<NumberingDestination> {
            LoggedIn(access) {
                NumberingRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** The tax profile, its history and its versions (UC-A02). */
private fun NavGraphBuilder.taxProfileScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<TaxProfileDestination> {
        LoggedIn(access) {
            TaxProfileRoute(
                onClose = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(TaxProfileHistoryDestination) },
            )
        }
    }
    composable<TaxProfileHistoryDestination> {
        LoggedIn(access) {
            TaxProfileHistoryRoute(
                onBack = { navController.popBackStack() },
                onOpenVersion = { number -> navController.navigate(TaxProfileVersionDestination(number)) },
            )
        }
    }
    composable<TaxProfileVersionDestination> {
        LoggedIn(access) {
            TaxProfileVersionRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The taxes and the preview of a line (UC-A07). */
private fun NavGraphBuilder.taxScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<TaxesDestination> {
        LoggedIn(access) {
            TaxListRoute(
                onBack = { navController.popBackStack() },
                onOpenTax = { id -> navController.navigate(TaxEditorDestination(id)) },
                onNewTax = { navController.navigate(TaxEditorDestination()) },
                onOpenPreview = { navController.navigate(LinePreviewDestination) },
            )
        }
    }
    composable<TaxEditorDestination> {
        LoggedIn(access) {
            TaxEditorRoute(onClose = { navController.popBackStack() })
        }
    }
    composable<LinePreviewDestination> {
        LoggedIn(access) {
            LinePreviewRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The items of the catalogue (UC-B01, UC-B04, UC-B07). */
private fun NavGraphBuilder.itemScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<ItemsDestination> {
        LoggedIn(access) {
            ItemListRoute(
                onBack = { navController.popBackStack() },
                onOpenItem = { id -> navController.navigate(ItemEditorDestination(id)) },
                onNewItem = { navController.navigate(ItemEditorDestination()) },
            )
        }
    }
    composable<ItemEditorDestination> {
        LoggedIn(access) {
            ItemEditorRoute(onClose = { navController.popBackStack() })
        }
    }
}

/** The customers (UC-B05 to B07). */
private fun NavGraphBuilder.customerScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<CustomersDestination> {
        LoggedIn(access) {
            CustomerListRoute(
                onBack = { navController.popBackStack() },
                onOpenCustomer = { id -> navController.navigate(CustomerEditorDestination(id)) },
                onNewCustomer = { navController.navigate(CustomerEditorDestination()) },
            )
        }
    }
    composable<CustomerEditorDestination> {
        LoggedIn(access) {
            CustomerEditorRoute(
                onClose = { navController.popBackStack() },
                onSaved = { id ->
                    // The screen that asked for the customer learns which one was stored.
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVED_CUSTOMER, id)
                    navController.popBackStack()
                },
            )
        }
    }
}

/** Key under which the customer editor leaves the identifier of the customer it stored. */
private const val SAVED_CUSTOMER = "savedCustomer"

/** The cash register, the customer of the sale, the sales on hold and the issued invoice (UC-C01 to C03). */
private fun NavGraphBuilder.saleScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<SaleDestination> {
        LoggedIn(access) {
            SaleRoute(
                onBack = { navController.popBackStack() },
                onChooseCustomer = { navController.navigate(SaleCustomerDestination) },
                onOpenHeld = { navController.navigate(HeldSalesDestination) },
                onOpenTransmission = { navController.navigate(TransmissionDestination) },
                onIssued = { id ->
                    navController.navigate(InvoiceIssuedDestination(id)) {
                        popUpTo<SaleDestination> { inclusive = true }
                    }
                },
            )
        }
    }
    composable<SaleCustomerDestination> { entry ->
        LoggedIn(access) {
            val created by entry.savedStateHandle.getStateFlow<Long?>(SAVED_CUSTOMER, null).collectAsState()
            SaleCustomerRoute(
                createdCustomerId = created,
                onClose = { navController.popBackStack() },
                onNewCustomer = { navController.navigate(CustomerEditorDestination()) },
            )
        }
    }
    composable<TransmissionDestination> {
        LoggedIn(access) {
            TransmissionRoute(
                onBack = { navController.popBackStack() },
                onOpenInvoice = { id -> navController.navigate(InvoiceDestination(id)) },
            )
        }
    }
    composable<HeldSalesDestination> {
        LoggedIn(access) {
            HeldSalesRoute(onClose = { navController.popBackStack() })
        }
    }
    composable<InvoiceIssuedDestination> {
        LoggedIn(access) {
            InvoiceIssuedRoute(
                onNewSale = {
                    navController.navigate(SaleDestination) {
                        popUpTo<InvoiceIssuedDestination> { inclusive = true }
                    }
                },
                onHome = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The two main screens, each inside the navigation that leads to the other: the home screen
 * ("Register") and the issued invoices (UC-C08). They replace each other without a transition, so
 * the navigation does not move.
 */
private fun NavGraphBuilder.mainTabs(access: AccessState.Ready, navController: NavHostController) {
    val openTab: (MainTab) -> Unit = { tab ->
        navController.navigate(tab.destination) {
            // The home screen stays under the other tab: "back" returns to it.
            popUpTo<HomeDestination> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    composable<HomeDestination>(
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        LoggedIn(access) { session ->
            MainTabs(selected = MainTab.REGISTER, onSelect = openTab) {
                HomeRoute(
                    userName = session.userName,
                    actions = HomeActions(
                        onOpenSettings = { navController.navigate(SettingsDestination) },
                        onOpenSale = { navController.navigate(SaleDestination) },
                        onOpenItems = { navController.navigate(ItemsDestination) },
                        onOpenCustomers = { navController.navigate(CustomersDestination) },
                        onOpenInvoices = { openTab(MainTab.INVOICES) },
                        onOpenInvoice = { id -> navController.navigate(InvoiceDestination(id)) },
                    ),
                    onOpenTransmission = { navController.navigate(TransmissionDestination) },
                )
            }
        }
    }
    composable<InvoicesDestination>(
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        LoggedIn(access) {
            MainTabs(selected = MainTab.INVOICES, onSelect = openTab) {
                InvoiceListRoute(onOpenInvoice = { id -> navController.navigate(InvoiceDestination(id)) })
            }
        }
    }
}

/** The connection to the EBMS and the log of the exchanges (UC-A03 to A05, UC-D08). */
private fun NavGraphBuilder.ebmsScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<EbmsConnectionDestination> {
        LoggedIn(access) {
            EbmsConnectionRoute(
                onBack = { navController.popBackStack() },
                onOpenExchanges = { navController.navigate(ExchangeLogDestination) },
            )
        }
    }
    composable<ExchangeLogDestination> {
        LoggedIn(access) {
            ExchangeLogRoute(
                onBack = { navController.popBackStack() },
                onOpenExchange = { id -> navController.navigate(ExchangeDestination(id)) },
            )
        }
    }
    composable<ExchangeDestination> {
        LoggedIn(access) {
            ExchangeRoute(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Shows [content] only with an open session. During the instant between a logout and the switch
 * to the login screen, the screens of the application are not composed at all.
 */
@Composable
private fun LoggedIn(access: AccessState.Ready, content: @Composable (Session) -> Unit) {
    if (access is AccessState.LoggedIn) {
        content(access.session)
    }
}
