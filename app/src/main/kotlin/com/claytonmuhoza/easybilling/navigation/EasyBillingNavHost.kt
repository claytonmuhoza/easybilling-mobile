package com.claytonmuhoza.easybilling.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.claytonmuhoza.easybilling.domain.identity.Session
import com.claytonmuhoza.easybilling.ebms.EbmsConnectionRoute
import com.claytonmuhoza.easybilling.ebms.ExchangeLogRoute
import com.claytonmuhoza.easybilling.ebms.ExchangeRoute
import com.claytonmuhoza.easybilling.home.HomeRoute
import com.claytonmuhoza.easybilling.login.LoginRoute
import com.claytonmuhoza.easybilling.recovery.ResetPinRoute
import com.claytonmuhoza.easybilling.setup.SetupRoute
import com.claytonmuhoza.easybilling.taxes.LinePreviewRoute
import com.claytonmuhoza.easybilling.taxes.TaxEditorRoute
import com.claytonmuhoza.easybilling.taxes.TaxListRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileHistoryRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileRoute
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileVersionRoute

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
        composable<HomeDestination> {
            LoggedIn(access) { session ->
                HomeRoute(
                    userName = session.userName,
                    onLogOut = onLogOut,
                    onOpenTaxProfile = { navController.navigate(TaxProfileDestination) },
                    onOpenEbmsConnection = { navController.navigate(EbmsConnectionDestination) },
                    onOpenTaxes = { navController.navigate(TaxesDestination) },
                )
            }
        }
        taxProfileScreens(access, navController)
        ebmsScreens(access, navController)
        taxScreens(access, navController)
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
