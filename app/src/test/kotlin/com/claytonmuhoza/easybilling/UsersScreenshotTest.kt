package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.claytonmuhoza.easybilling.access.LocalPermissions
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserProblem
import com.claytonmuhoza.easybilling.home.CatalogStatus
import com.claytonmuhoza.easybilling.home.CustomersStatus
import com.claytonmuhoza.easybilling.home.HomeActions
import com.claytonmuhoza.easybilling.home.HomeScreen
import com.claytonmuhoza.easybilling.home.HomeUiState
import com.claytonmuhoza.easybilling.home.ReadinessStatus
import com.claytonmuhoza.easybilling.home.SalesStatus
import com.claytonmuhoza.easybilling.login.BusinessIdentity
import com.claytonmuhoza.easybilling.login.LoginAccount
import com.claytonmuhoza.easybilling.login.LoginActions
import com.claytonmuhoza.easybilling.login.LoginScreen
import com.claytonmuhoza.easybilling.login.LoginUiState
import com.claytonmuhoza.easybilling.login.NewPinStep
import com.claytonmuhoza.easybilling.navigation.MainTab
import com.claytonmuhoza.easybilling.navigation.MainTabs
import com.claytonmuhoza.easybilling.settings.SettingsActions
import com.claytonmuhoza.easybilling.settings.SettingsScreen
import com.claytonmuhoza.easybilling.settings.SettingsUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.users.UserEditorActions
import com.claytonmuhoza.easybilling.users.UserEditorScreen
import com.claytonmuhoza.easybilling.users.UserEditorStep
import com.claytonmuhoza.easybilling.users.UserEditorUiState
import com.claytonmuhoza.easybilling.users.UserListActions
import com.claytonmuhoza.easybilling.users.UserListScreen
import com.claytonmuhoza.easybilling.users.UserListUiState
import com.claytonmuhoza.easybilling.users.UserNotice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the accounts and their editor, of the login with several accounts and a
 * temporary PIN, and of the screens a cashier and a storekeeper see (UC-H01), in every
 * [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class UsersScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), null)

    private fun user(id: Long, name: String, vararg roles: Role, active: Boolean = true, temporary: Boolean = false) =
        User(UserId(id), name, roles.toSet(), secret, active = active, pinTemporary = temporary)

    private val alice = user(1, "Alice Niyonzima", Role.ADMINISTRATOR)
    private val bob = user(2, "Bob Ndayishimiye", Role.CASHIER, Role.STOREKEEPER, temporary = true)
    private val claude = user(3, "Claudine Irakoze", Role.MANAGER)
    private val denis = user(4, "Denis Hakizimana", Role.CASHIER, active = false)

    private val business = BusinessIdentity(name = "Boutique Umuco SPRL", tin = "4000000001")

    private val shop = HomeUiState(
        readiness = ReadinessStatus.Known(
            BillingReadiness(
                mapOf(
                    ReadinessStep.TAX_PROFILE to true,
                    ReadinessStep.EBMS_CONNECTION to true,
                    ReadinessStep.VAT to true,
                    ReadinessStep.NUMBERING to true,
                ),
            ),
        ),
        catalogStatus = CatalogStatus.Counted(active = 12, withoutVat = 0),
        customersStatus = CustomersStatus.Counted(active = 34),
        salesStatus = SalesStatus.Counted(held = 0),
    )

    /** Shows [content] as a user holding [roles] sees it. */
    @Composable
    private fun asRoles(vararg roles: Role, content: @Composable () -> Unit) {
        val permissions = Permission.entries.filter { FixedPermissionPolicy.allows(roles.toSet(), it) }.toSet()
        CompositionLocalProvider(LocalPermissions provides permissions, content = content)
    }

    @Composable
    private fun editor(state: UserEditorUiState) = UserEditorScreen(state, UserEditorActions())

    @Test
    fun userList() = capture("users") {
        UserListScreen(UserListUiState.Ready(listOf(alice, claude, bob, denis)), alice.id.value, UserListActions())
    }

    @Test
    fun userListLoading() = capture("users_loading", animated = true) {
        UserListScreen(UserListUiState.Loading, alice.id.value, UserListActions())
    }

    @Test
    fun userListFailed() = capture("users_failed") {
        UserListScreen(UserListUiState.Failed, alice.id.value, UserListActions())
    }

    @Test
    fun newUser() = capture("user_new") {
        editor(UserEditorUiState.Editing(existing = null, name = "Bob", roles = setOf(Role.CASHIER)))
    }

    @Test
    fun newUserRefused() = capture("user_new_refused") {
        editor(UserEditorUiState.Editing(existing = null, name = "alice niyonzima", problem = UserProblem.NAME_TAKEN))
    }

    @Test
    fun newUserTemporaryPin() = capture("user_new_pin") {
        editor(
            UserEditorUiState.Editing(
                existing = null,
                name = "Bob",
                roles = setOf(Role.CASHIER),
                step = UserEditorStep.CONFIRMATION,
                pin = "111111",
                pinError = PinProblem.MISMATCH,
            ),
        )
    }

    @Test
    fun existingUser() = capture("user_existing") {
        editor(UserEditorUiState.Editing(existing = bob, notice = UserNotice.PIN_RESET))
    }

    @Test
    fun ownAccount() = capture("user_own_account") {
        editor(UserEditorUiState.Editing(existing = alice, notice = UserNotice.OWN_ACCOUNT))
    }

    @Test
    fun loginChooseUser() = capture("login_choose_user") {
        LoginScreen(
            state = LoginUiState.ChooseUser(
                business = business,
                accounts = listOf(alice, claude, bob).map { LoginAccount(it.id.value, it.name) },
            ),
            actions = LoginActions(),
        )
    }

    @Test
    fun loginAccountWithoutRecoveryCode() = capture("login_ask_administrator") {
        LoginScreen(
            state = LoginUiState.Ready(
                userName = bob.name,
                business = business,
                canChangeUser = true,
                hasRecoveryCode = false,
            ),
            actions = LoginActions(),
        )
    }

    @Test
    fun loginTemporaryPin() = capture("login_new_pin") {
        LoginScreen(
            state = LoginUiState.NewPin(userName = bob.name, step = NewPinStep.PIN, pin = "111"),
            actions = LoginActions(),
        )
    }

    @Test
    fun loginSameAsTemporary() = capture("login_new_pin_same") {
        LoginScreen(
            state = LoginUiState.NewPin(userName = bob.name, pin = "111111", sameAsTemporary = true),
            actions = LoginActions(),
        )
    }

    @Test
    fun homeOfCashier() = capture("home_cashier") {
        asRoles(Role.CASHIER) {
            MainTabs(selected = MainTab.REGISTER, onSelect = {}) {
                HomeScreen(userName = bob.name, state = shop, actions = HomeActions())
            }
        }
    }

    @Test
    fun homeOfStorekeeper() = capture("home_storekeeper") {
        asRoles(Role.STOREKEEPER) {
            MainTabs(selected = MainTab.REGISTER, onSelect = {}) {
                HomeScreen(userName = bob.name, state = shop, actions = HomeActions())
            }
        }
    }

    @Test
    fun settingsOfCashier() = capture("settings_cashier") {
        asRoles(Role.CASHIER) { SettingsScreen(bob.name, SettingsUiState(), SettingsActions()) }
    }

    @Test
    fun settingsOfManager() = capture("settings_manager") {
        asRoles(Role.MANAGER) { SettingsScreen(claude.name, SettingsUiState(), SettingsActions()) }
    }

    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
