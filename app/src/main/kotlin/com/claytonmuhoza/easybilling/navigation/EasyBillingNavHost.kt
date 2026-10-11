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
import com.claytonmuhoza.easybilling.documents.DocumentSettingsRoute
import com.claytonmuhoza.easybilling.documents.PrinterSettingsRoute
import com.claytonmuhoza.easybilling.domain.identity.Permission
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
import com.claytonmuhoza.easybilling.login.SwitchUserRoute
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
import com.claytonmuhoza.easybilling.users.DiscountCapRoute
import com.claytonmuhoza.easybilling.users.UserEditorRoute
import com.claytonmuhoza.easybilling.users.UserListRoute

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
            LoginRoute(onForgotPin = { userId -> navController.navigate(ResetPinDestination(userId)) })
        }
        composable<ResetPinDestination> {
            ResetPinRoute(onClose = { navController.popBackStack() })
        }
        composable<SwitchUserDestination> {
            LoggedIn(access) {
                SwitchUserRoute(onClose = { navController.popBackStack() })
            }
        }
        mainTabs(access, navController)
        settingsScreens(access, navController, onLogOut)
        userScreens(access, navController)
        taxProfileScreens(access, navController)
        ebmsScreens(access, navController)
        taxScreens(access, navController)
        itemScreens(access, navController)
        customerScreens(access, navController)
        saleScreens(access, navController)
        invoiceScreens(access, navController)
    }
}

/** The settings, the header of the invoices, the printer and the numbering (UC-A06, UC-A08, UC-A09). */
private fun NavGraphBuilder.settingsScreens(
    access: AccessState.Ready,
    navController: NavHostController,
    onLogOut: () -> Unit,
) {
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
                    onOpenDocuments = { navController.navigate(DocumentSettingsDestination) },
                    onOpenPrinter = { navController.navigate(PrinterSettingsDestination) },
                    onOpenUsers = { navController.navigate(UsersDestination) },
                    onOpenDiscountCap = { navController.navigate(DiscountCapDestination) },
                ),
            )
        }
    }
    composable<DocumentSettingsDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_PRINTING) {
            DocumentSettingsRoute(onBack = { navController.popBackStack() })
        }
    }
    composable<PrinterSettingsDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_PRINTING) {
            PrinterSettingsRoute(onBack = { navController.popBackStack() })
        }
    }
    composable<NumberingDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            NumberingRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The tax profile, its history and its versions (UC-A02). */
private fun NavGraphBuilder.taxProfileScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<TaxProfileDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            TaxProfileRoute(
                onClose = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(TaxProfileHistoryDestination) },
            )
        }
    }
    composable<TaxProfileHistoryDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            TaxProfileHistoryRoute(
                onBack = { navController.popBackStack() },
                onOpenVersion = { number -> navController.navigate(TaxProfileVersionDestination(number)) },
            )
        }
    }
    composable<TaxProfileVersionDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            TaxProfileVersionRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The taxes and the preview of a line (UC-A07). */
private fun NavGraphBuilder.taxScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<TaxesDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            TaxListRoute(
                onBack = { navController.popBackStack() },
                onOpenTax = { id -> navController.navigate(TaxEditorDestination(id)) },
                onNewTax = { navController.navigate(TaxEditorDestination()) },
                onOpenPreview = { navController.navigate(LinePreviewDestination) },
            )
        }
    }
    composable<TaxEditorDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            TaxEditorRoute(onClose = { navController.popBackStack() })
        }
    }
    composable<LinePreviewDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            LinePreviewRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The items of the catalogue (UC-B01, UC-B04, UC-B07). */
private fun NavGraphBuilder.itemScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<ItemsDestination> {
        LoggedIn(access, navController, Permission.VIEW_ITEMS) {
            ItemListRoute(
                onBack = { navController.popBackStack() },
                onOpenItem = { id -> navController.navigate(ItemEditorDestination(id)) },
                onNewItem = { navController.navigate(ItemEditorDestination()) },
            )
        }
    }
    composable<ItemEditorDestination> {
        LoggedIn(access, navController, Permission.EDIT_ITEMS) {
            ItemEditorRoute(onClose = { navController.popBackStack() })
        }
    }
}

/** The customers (UC-B05 to B07). */
private fun NavGraphBuilder.customerScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<CustomersDestination> {
        LoggedIn(access, navController, Permission.MANAGE_CUSTOMERS) {
            CustomerListRoute(
                onBack = { navController.popBackStack() },
                onOpenCustomer = { id -> navController.navigate(CustomerEditorDestination(id)) },
                onNewCustomer = { navController.navigate(CustomerEditorDestination()) },
            )
        }
    }
    composable<CustomerEditorDestination> {
        LoggedIn(access, navController, Permission.MANAGE_CUSTOMERS) {
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

/** The accounts of the users and their roles (UC-H01). */
private fun NavGraphBuilder.userScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<UsersDestination> {
        LoggedIn(access, navController, Permission.MANAGE_USERS) { session ->
            UserListRoute(
                currentUserId = session.userId.value,
                onBack = { navController.popBackStack() },
                onOpenUser = { id -> navController.navigate(UserEditorDestination(id)) },
                onNewUser = { navController.navigate(UserEditorDestination()) },
            )
        }
    }
    composable<UserEditorDestination> {
        LoggedIn(access, navController, Permission.MANAGE_USERS) {
            UserEditorRoute(onClose = { navController.popBackStack() })
        }
    }
    composable<DiscountCapDestination> {
        LoggedIn(access, navController, Permission.MANAGE_USERS) {
            DiscountCapRoute(onBack = { navController.popBackStack() })
        }
    }
}

/** The cash register, the customer of the sale, the sales on hold and the issued invoice (UC-C01 to C03). */
private fun NavGraphBuilder.saleScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<SaleDestination> {
        LoggedIn(access, navController, Permission.SELL) {
            SaleRoute(
                onBack = { navController.popBackStack() },
                onChooseCustomer = { navController.navigate(SaleCustomerDestination) },
                onOpenHeld = { navController.navigate(HeldSalesDestination) },
                onOpenTransmission = { navController.navigate(TransmissionDestination) },
                onSwitchUser = { navController.navigate(SwitchUserDestination) },
                onIssued = { id ->
                    navController.navigate(InvoiceIssuedDestination(id)) {
                        popUpTo<SaleDestination> { inclusive = true }
                    }
                },
            )
        }
    }
    composable<SaleCustomerDestination> { entry ->
        LoggedIn(access, navController, Permission.SELL) {
            val created by entry.savedStateHandle.getStateFlow<Long?>(SAVED_CUSTOMER, null).collectAsState()
            SaleCustomerRoute(
                createdCustomerId = created,
                onClose = { navController.popBackStack() },
                onNewCustomer = { navController.navigate(CustomerEditorDestination()) },
            )
        }
    }
    composable<TransmissionDestination> {
        LoggedIn(access, navController, Permission.VIEW_TRANSMISSION) {
            TransmissionRoute(
                onBack = { navController.popBackStack() },
                onOpenInvoice = { id -> navController.navigate(InvoiceDestination(id)) },
                onOpenExchanges = { navController.navigate(ExchangeLogDestination) },
            )
        }
    }
    composable<HeldSalesDestination> {
        LoggedIn(access, navController, Permission.SELL) {
            HeldSalesRoute(onClose = { navController.popBackStack() })
        }
    }
    composable<InvoiceIssuedDestination> {
        LoggedIn(access, navController, Permission.SELL) {
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
                        onSwitchUser = { navController.navigate(SwitchUserDestination) },
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
        LoggedIn(access, navController, Permission.VIEW_INVOICES) {
            MainTabs(selected = MainTab.INVOICES, onSelect = openTab) {
                InvoiceListRoute(onOpenInvoice = { id -> navController.navigate(InvoiceDestination(id)) })
            }
        }
    }
}

/** The connection to the EBMS and the log of the exchanges (UC-A03 to A05, UC-D08). */
private fun NavGraphBuilder.ebmsScreens(access: AccessState.Ready, navController: NavHostController) {
    composable<EbmsConnectionDestination> {
        LoggedIn(access, navController, Permission.CONFIGURE_BILLING) {
            EbmsConnectionRoute(
                onBack = { navController.popBackStack() },
                onOpenExchanges = { navController.navigate(ExchangeLogDestination) },
            )
        }
    }
    composable<ExchangeLogDestination> {
        LoggedIn(access, navController, Permission.VIEW_TRANSMISSION) {
            ExchangeLogRoute(
                onBack = { navController.popBackStack() },
                onOpenExchange = { id -> navController.navigate(ExchangeDestination(id)) },
            )
        }
    }
    composable<ExchangeDestination> {
        LoggedIn(access, navController, Permission.VIEW_TRANSMISSION) {
            ExchangeRoute(onBack = { navController.popBackStack() })
        }
    }
}
