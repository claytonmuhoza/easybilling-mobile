package com.claytonmuhoza.easybilling.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.home.HomeScreen
import com.claytonmuhoza.easybilling.login.LoginRoute
import com.claytonmuhoza.easybilling.recovery.ResetPinRoute
import com.claytonmuhoza.easybilling.setup.SetupRoute

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
            if (access is AccessState.LoggedIn) {
                HomeScreen(userName = access.session.userName, onLogOut = onLogOut)
            }
        }
    }
}
