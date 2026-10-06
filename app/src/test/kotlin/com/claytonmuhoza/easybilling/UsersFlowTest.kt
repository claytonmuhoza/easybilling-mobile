package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationFrameTags
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SetUserActive
import com.claytonmuhoza.easybilling.domain.identity.UpdateUser
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettingsRepository
import com.claytonmuhoza.easybilling.testing.enterPin
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.press
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressInSettings
import com.claytonmuhoza.easybilling.testing.pressKey
import com.claytonmuhoza.easybilling.testing.type
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Users and roles (UC-H01): the accounts an administrator creates, the choice of the account and
 * the temporary PIN at the login, and the screens each role sees. The administrator "Alice"
 * exists with the PIN 123456.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class UsersFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var createUser: CreateUser

    @Inject
    lateinit var setUserActive: SetUserActive

    @Inject
    lateinit var updateUser: UpdateUser

    @Inject
    lateinit var changeTemporaryPin: ChangeTemporaryPin

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var discountSettings: DiscountSettingsRepository

    @Inject
    lateinit var users: UserRepository

    @Inject
    lateinit var auditStore: AuditStore

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var alice: User

    @Before
    fun createAlice() {
        hiltRule.inject()
        alice = assertIs<CreateAdministrator.Result.Created>(
            runBlocking { createAdministrator("Alice", ALICE_PIN, ALICE_PIN) },
        ).user
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Creates an account with the temporary PIN [TEMPORARY_PIN], as Alice. */
    private fun account(name: String, vararg roles: Role): User = assertIs<CreateUser.Result.Created>(
        runBlocking { createUser(UserForm(name, roles.toSet()), TEMPORARY_PIN, TEMPORARY_PIN, alice.id) },
    ).user

    /** Picks [name] among the accounts, then types [pin]. */
    private fun logInAs(name: String, pin: String) {
        composeRule.waitForText("Who is logging in?")
        composeRule.press(name)
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(pin)
        composeRule.pressKey("Log in")
    }

    /** Logs in with the temporary PIN and replaces it with [pin], up to the home screen. */
    private fun firstLogin(name: String, pin: String = OWN_PIN) {
        logInAs(name, TEMPORARY_PIN)
        composeRule.waitForText("$name, choose your PIN")
        composeRule.enterPin(pin)
        composeRule.pressKey("Continue")
        composeRule.waitForText("Confirm the PIN")
        composeRule.enterPin(pin)
        composeRule.pressKey("Save the PIN and log in")
        composeRule.waitForText("Welcome, $name")
    }

    private fun logOut() {
        composeRule.openSettings()
        composeRule.waitForText("Log out").performScrollTo().performClick()
    }

    private fun auditedActions(): List<String> = runBlocking { auditStore.readAll().map { it.action } }

    @Test
    fun `an administrator creates a cashier, who replaces the temporary PIN at the first login`() {
        launch()
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(ALICE_PIN)
        composeRule.pressKey("Log in")
        composeRule.pressInSettings("Users")
        composeRule.waitForText("Alice (you)")

        composeRule.press("New user")
        composeRule.type("Name", "Bob")
        composeRule.pressInForm("Cashier")
        composeRule.pressInForm("Continue")
        composeRule.waitForText("Temporary PIN")
        composeRule.enterPin(TEMPORARY_PIN)
        composeRule.pressKey("Continue")
        composeRule.waitForText("Confirm the PIN")
        composeRule.enterPin(TEMPORARY_PIN)
        composeRule.pressKey("Create the account")

        composeRule.waitForText("Bob")
        composeRule.onNodeWithText("PIN to change").assertExists()
        val bob = runBlocking { users.findAll().single { it.name == "Bob" } }
        assertEquals(setOf(Role.CASHIER), bob.roles)
        assertTrue(bob.pinTemporary)

        composeRule.onNodeWithContentDescription("Back").performClick()
        logOut()
        firstLogin("Bob")
        assertEquals(false, runBlocking { users.find(bob.id) }?.pinTemporary)
        assertTrue("TEMPORARY_PIN_CHANGED" in auditedActions(), auditedActions().toString())
    }

    @Test
    fun `a new account is refused without a role, and its PIN is kept once the form is fixed`() {
        launch()
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(ALICE_PIN)
        composeRule.pressKey("Log in")
        composeRule.pressInSettings("Users")
        composeRule.waitForText("Alice (you)")
        composeRule.press("New user")
        composeRule.type("Name", "alice")
        composeRule.pressInForm("Continue")
        composeRule.enterPin(TEMPORARY_PIN)
        composeRule.pressKey("Continue")
        composeRule.enterPin(TEMPORARY_PIN)
        composeRule.pressKey("Create the account")

        composeRule.waitForText("Another user already has this name")
        composeRule.onNodeWithText("Tick at least one role").assertDoesNotExist()
        assertEquals(1, runBlocking { users.count() })
    }

    @Test
    fun `the login offers the active accounts only, and a disabled one is not offered`() {
        val bob = account("Bob", Role.CASHIER)
        launch()
        composeRule.waitForText("Who is logging in?")
        composeRule.onNodeWithText("Bob").assertExists()
        scenario?.close()

        runBlocking { setUserActive(bob.id, active = false, actor = alice.id) }
        launch()
        composeRule.waitForText("Hello, Alice")
        composeRule.onNodeWithText("Who is logging in?").assertDoesNotExist()
    }

    @Test
    fun `an account without a recovery code asks an administrator for a forgotten PIN`() {
        account("Bob", Role.CASHIER)
        launch()
        composeRule.waitForText("Who is logging in?")
        composeRule.press("Bob")

        composeRule.waitForText("Forgot your PIN? Ask an administrator to reset it.")
        composeRule.onNodeWithText("Forgot your PIN?").assertDoesNotExist()
        composeRule.waitForText("Not you? Change user").performScrollTo().performClick()
        composeRule.waitForText("Who is logging in?")
        composeRule.press("Alice")
        composeRule.waitForText("Forgot your PIN?")
    }

    @Test
    fun `a cashier sells and serves the customers but sees neither the configuration nor the users`() {
        account("Bob", Role.CASHIER)
        launch()
        firstLogin("Bob")

        composeRule.onNodeWithText("Items").assertExists()
        composeRule.onNodeWithText("Customers").assertExists()
        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertExists()
        composeRule.openSettings()
        composeRule.waitForText("Log out")
        composeRule.onNodeWithText("Tax profile").assertDoesNotExist()
        composeRule.onNodeWithText("Printer").assertDoesNotExist()
        composeRule.onNodeWithText("Users").assertDoesNotExist()
        composeRule.onNodeWithText("Logged in as Bob", substring = true).assertExists()
    }

    @Test
    fun `a cashier sees the items but cannot create one`() {
        account("Bob", Role.CASHIER)
        launch()
        firstLogin("Bob")

        composeRule.press("Items")
        composeRule.waitForText("No item yet", substring = true)
        composeRule.onNodeWithText("New item").assertDoesNotExist()
    }

    @Test
    fun `a storekeeper only sees the items, without the sale, the invoices nor the customers`() {
        account("Carine", Role.STOREKEEPER)
        launch()
        firstLogin("Carine")

        composeRule.onNodeWithText("Items").assertExists()
        composeRule.onNodeWithText("Customers").assertDoesNotExist()
        composeRule.onNodeWithText("New sale").assertDoesNotExist()
        composeRule.onNodeWithTag(NavigationFrameTags.BAR).assertDoesNotExist()
        composeRule.press("Items")
        composeRule.waitForText("No item yet")
        assertEquals(2, composeRule.onAllNodesWithTextCount("New item"))
    }

    @Test
    fun `a screen whose right is taken away while it is shown is left at once`() {
        val bob = account("Bob", Role.ADMINISTRATOR, Role.CASHIER)
        launch()
        firstLogin("Bob")
        composeRule.pressInSettings("Users")
        composeRule.waitForText("Bob (you)")

        runBlocking { updateUser(bob.id, UserForm("Bob", setOf(Role.CASHIER)), alice.id) }

        composeRule.waitForText("Log out")
        composeRule.onNodeWithText("Users").assertDoesNotExist()
        composeRule.onNodeWithText("Tax profile").assertDoesNotExist()
    }

    @Test
    fun `an administrator cannot disable their own account`() {
        launch()
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(ALICE_PIN)
        composeRule.pressKey("Log in")
        composeRule.pressInSettings("Users")
        composeRule.waitForText("Alice (you)").performClick()

        composeRule.pressInForm("Disable the account")

        composeRule.waitForText("You cannot disable the account you are logged in with")
        assertEquals(true, runBlocking { users.find(alice.id) }?.active)
    }

    @Test
    fun `an administrator resets the PIN of a user, who chooses a new one at the next login`() {
        val bob = account("Bob", Role.MANAGER)
        launch()
        firstLogin("Bob")
        logOut()
        logInAs("Alice", ALICE_PIN)
        composeRule.pressInSettings("Users")
        composeRule.waitForText("Bob").performClick()

        composeRule.pressInForm("Reset the PIN")
        composeRule.enterPin("333333")
        composeRule.pressKey("Continue")
        composeRule.enterPin("333333")
        composeRule.pressKey("Reset the PIN")

        composeRule.waitForText("New temporary PIN saved", substring = true)
        assertEquals(true, runBlocking { users.find(bob.id) }?.pinTemporary)
    }

    /** Logs in as Alice with her PIN: she is the only account at first. */
    private fun logInAlice() {
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(ALICE_PIN)
        composeRule.pressKey("Log in")
        composeRule.waitForText("Welcome, Alice")
    }

    @Test
    fun `the register is handed over from the home screen to the account picked`() {
        launch()
        logInAlice()
        account("Bob", Role.CASHIER)
        account("Carine", Role.STOREKEEPER)
        val bob = runBlocking { users.findAll().single { it.name == "Bob" } }
        runBlocking { changeTemporaryPin(bob.id, TEMPORARY_PIN, OWN_PIN, OWN_PIN) }
        runBlocking { sessions.switchTo(alice) }

        composeRule.onNodeWithContentDescription("Change user").performClick()
        composeRule.waitForText("Who takes over?")
        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.press("Bob")
        composeRule.enterPin(OWN_PIN)
        composeRule.pressKey("Change user")

        composeRule.waitForText("Welcome, Bob")
        assertEquals("Bob", sessions.current.value?.userName)
    }

    @Test
    fun `an account whose PIN is temporary cannot take the register over`() {
        launch()
        logInAlice()
        account("Bob", Role.CASHIER)

        composeRule.onNodeWithContentDescription("Change user").performClick()
        composeRule.waitForText("Enter your PIN to continue")
        composeRule.enterPin(TEMPORARY_PIN)
        composeRule.pressKey("Change user")

        composeRule.waitForText("First login of this account: log out, then log in with it.")
        assertEquals("Alice", sessions.current.value?.userName)
    }

    @Test
    fun `an administrator caps the discount of the cashier`() {
        launch()
        logInAlice()
        composeRule.pressInSettings("Discount cap")
        composeRule.waitForText("Limit the discount of the cashier").performClick()
        composeRule.type("Highest discount without approval (%)", "100")
        composeRule.press("Save")
        composeRule.waitForText("Enter a percentage from 0 to 99.99, with two decimals at most.")

        composeRule.replaceInForm("Highest discount without approval (%)", "12,5")
        composeRule.press("Save")

        composeRule.waitForText("Cap saved.")
        assertEquals(0, BigDecimal("12.5").compareTo(runBlocking { discountSettings.read().cashierCap }))
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForText("Cashier: 12.5 %")
    }

    private companion object {
        const val ALICE_PIN = "123456"
        const val TEMPORARY_PIN = "111111"
        const val OWN_PIN = "222222"
    }
}
