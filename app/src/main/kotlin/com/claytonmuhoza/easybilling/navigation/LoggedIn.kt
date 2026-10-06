package com.claytonmuhoza.easybilling.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import com.claytonmuhoza.easybilling.access.AccessState
import com.claytonmuhoza.easybilling.access.LocalPermissions
import com.claytonmuhoza.easybilling.access.permissions
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.Session

/**
 * Shows [content] only with an open session. During the instant between a logout and the switch
 * to the login screen, the screens of the application are not composed at all. The screens learn
 * what the user may do through [LocalPermissions].
 */
@Composable
internal fun LoggedIn(access: AccessState.Ready, content: @Composable (Session) -> Unit) {
    if (access is AccessState.LoggedIn) {
        CompositionLocalProvider(LocalPermissions provides access.session.permissions()) {
            content(access.session)
        }
    }
}

/**
 * Shows [content] only with an open session allowed [requires]. A screen the user is not allowed
 * is left at once: the screens hide the way to it, but the roles of an account may change while
 * it is shown (UC-H01).
 */
@Composable
internal fun LoggedIn(
    access: AccessState.Ready,
    navController: NavHostController,
    requires: Permission,
    content: @Composable (Session) -> Unit,
) {
    val session = (access as? AccessState.LoggedIn)?.session ?: return
    if (session.can(requires)) {
        LoggedIn(access, content)
    } else {
        LaunchedEffect(requires) { navController.popBackStack() }
    }
}
