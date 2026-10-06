package com.claytonmuhoza.easybilling.users

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.UserProblem

/** @return the name of the role. */
@Composable
fun Role.label(): String = stringResource(
    when (this) {
        Role.ADMINISTRATOR -> R.string.role_administrator
        Role.MANAGER -> R.string.role_manager
        Role.CASHIER -> R.string.role_cashier
        Role.STOREKEEPER -> R.string.role_storekeeper
    },
)

/** @return what the role allows, in a few words. */
@Composable
fun Role.help(): String = stringResource(
    when (this) {
        Role.ADMINISTRATOR -> R.string.role_administrator_help
        Role.MANAGER -> R.string.role_manager_help
        Role.CASHIER -> R.string.role_cashier_help
        Role.STOREKEEPER -> R.string.role_storekeeper_help
    },
)

/** @return the names of the roles, in the order of [Role], separated by commas. */
@Composable
fun Set<Role>.labels(): String = Role.entries.filter { it in this }.map { it.label() }.joinToString(", ")

/** @return the message shown for an account that breaks a rule. */
@Composable
fun UserProblem.message(): String = stringResource(
    when (this) {
        UserProblem.NAME_BLANK -> R.string.error_name_blank
        UserProblem.NAME_TOO_LONG -> R.string.error_name_too_long
        UserProblem.NAME_TAKEN -> R.string.error_user_name_taken
        UserProblem.NO_ROLE -> R.string.error_user_no_role
        UserProblem.LAST_ADMINISTRATOR -> R.string.error_user_last_administrator
    },
)
