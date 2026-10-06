package com.claytonmuhoza.easybilling.access

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.Session

/**
 * What the logged in user may do, for the screens to hide what is not allowed. The use cases
 * refuse it too: hiding is only for the comfort of the user.
 *
 * Outside a session, and in the tests of a single screen, everything is allowed.
 */
val LocalPermissions = staticCompositionLocalOf { Permission.entries.toSet() }

/**
 * @param permission what the user wants to do.
 * @return true if the logged in user is allowed [permission].
 */
@Composable
@ReadOnlyComposable
fun allowed(permission: Permission): Boolean = permission in LocalPermissions.current

/** @return every permission one of the roles of the session gives. */
fun Session.permissions(): Set<Permission> = Permission.entries.filter { can(it) }.toSet()
