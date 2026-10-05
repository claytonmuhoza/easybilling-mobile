package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.configuration.EbmsConnectionForm
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.SaveEbmsConnection
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.ArchiveCustomer
import com.claytonmuhoza.easybilling.domain.customer.CreateCustomer
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerForm
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerSaveResult
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.typeInForm
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
import java.net.ServerSocket
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The customers (UC-B05, B06, B07), by the administrator "Alice", already logged in, against the
 * simulated OBR server over HTTPS.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class CustomersFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var customers: CustomerRepository

    @Inject
    lateinit var createCustomer: CreateCustomer

    @Inject
    lateinit var archiveCustomer: ArchiveCustomer

    @Inject
    lateinit var saveConnection: SaveEbmsConnection

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val company = CustomerForm(
        name = "Brasserie du Lac SA",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.LEGAL_PERSON,
        tin = ObrSimulator.KNOWN_TIN,
        address = "Bujumbura, Rohero",
        phone = "22 22 22 22",
        vatPayer = true,
    )
    private val walkIn = CustomerForm(
        name = "Aline Irakoze",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.NATURAL_PERSON,
        phone = "79 123 456",
    )

    @Before
    fun logInAlice() {
        hiltRule.inject()
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
        }
    }

    @After
    fun stop() {
        scenario.close()
        simulator.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Saves the address and the credentials of the simulated server, as the administrator would. */
    private fun connectToObr(serverUrl: String = simulator.baseUrl) = runBlocking {
        val form = EbmsConnectionForm(serverUrl, ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        assertEquals(SaveEbmsConnection.Result.Saved, saveConnection(EbmsEnvironment.TEST, form, alice.id))
    }

    /** Waits for the editor to close on the list, then for the storage to hold exactly one customer. */
    private fun savedCustomer(): Customer {
        composeRule.waitForText("Anonymous sales")
        composeRule.waitUntil(TIMEOUT_MILLIS) { active().size == 1 }
        return active().single()
    }

    private fun define(form: CustomerForm): Customer = runBlocking {
        assertIs<CustomerSaveResult.Saved>(createCustomer(form, alice.id)).customer
    }

    /** @param status what the home screen says of the customers, the last status it loads. */
    private fun openCustomers(status: String = "No customer") {
        composeRule.waitForText(status)
        composeRule.pressInForm("Customers")
        composeRule.waitForText("Anonymous sales")
    }

    private fun openNewCustomer() {
        composeRule.onAllNodesWithText("New customer").onFirst().performClick()
        composeRule.waitForText("Create the customer")
    }

    /**
     * Presses [button], which saves the form and closes it, and waits for the form to be gone. The
     * name typed in the form is also the one the list then shows: looked for too early, it is
     * found in the field of the form, which may be scrolled out of sight.
     */
    private fun saveAndLeave(button: String) {
        composeRule.pressInForm(button)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount(button) == 0 }
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    private fun active(): List<Customer> = runBlocking { customers.search("", archived = false, limit = 10) }

    private fun obrCalls(): List<String> =
        simulator.requests.map { it.url.encodedPath.removePrefix(ObrSimulator.API_PATH) }

    @Test
    fun `a natural person without a TIN is created without asking the OBR`() {
        launch()
        openCustomers()
        shown(EMPTY_MESSAGE)
        openNewCustomer()

        composeRule.pressInForm("Local")
        composeRule.pressInForm("Natural person")
        composeRule.typeInForm("Name", "Aline Irakoze")
        composeRule.typeInForm("Phone", "79 123 456")
        saveAndLeave("Create the customer")

        composeRule.waitForText("Aline Irakoze").assertIsDisplayed()
        composeRule.onNodeWithText("Local · Natural person").assertIsDisplayed()
        val customer = active().single()
        assertEquals("Aline Irakoze", customer.details.name)
        assertEquals("79 123 456", customer.details.phone)
        assertNull(customer.details.tin)
        assertEquals(emptyList(), obrCalls())
        assertEquals("CUSTOMER_CREATED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `a legal person is saved once the OBR recognises its TIN, under the name the OBR gives`() {
        connectToObr()
        launch()
        openCustomers()
        openNewCustomer()

        composeRule.pressInForm("Local")
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)
        composeRule.pressInForm("Registered for the VAT")
        saveAndLeave("Create the customer")

        composeRule.waitForText(ObrSimulator.KNOWN_TAXPAYER).assertIsDisplayed()
        composeRule.onNodeWithText("Local · Legal person · TIN 4000202020 · VAT payer").assertIsDisplayed()
        val customer = active().single()
        assertEquals(ObrSimulator.KNOWN_TAXPAYER, customer.details.name)
        val verified = VerifiedTin(ObrSimulator.KNOWN_TIN, ObrSimulator.KNOWN_TAXPAYER, clock.now())
        assertEquals(verified, customer.details.tin)
        assertTrue(customer.details.vatPayer)
        assertEquals(listOf("login/", "checkTIN/"), obrCalls())
    }

    @Test
    fun `checking the TIN fills the name with the one of the OBR, which can still be corrected`() {
        connectToObr()
        launch()
        openCustomers()
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Natural person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)

        composeRule.pressInForm("Check the TIN")

        shown("TIN recognised by the OBR: NDIKUMANA Jean Marie")
        composeRule.onNodeWithText("NDIKUMANA Jean Marie").assertExists()
        composeRule.replaceInForm("Name", "Jean Marie Ndikumana")
        saveAndLeave("Create the customer")

        val customer = savedCustomer()
        composeRule.waitForText("Jean Marie Ndikumana").assertIsDisplayed()
        assertEquals("Jean Marie Ndikumana", customer.details.name)
        assertEquals(ObrSimulator.KNOWN_TAXPAYER, customer.details.tin?.obrName)
    }

    @Test
    fun `a TIN the OBR does not know is refused and the customer is not saved`() {
        connectToObr()
        launch()
        openCustomers()
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("TIN", "999")
        composeRule.typeInForm("Name", "Société inconnue")

        composeRule.pressInForm("Create the customer")

        shown("The OBR does not know this TIN. A customer cannot be saved with it.")
        assertEquals(emptyList(), active())
    }

    @Test
    fun `when the OBR cannot be reached the customer is not saved, and is saved at the next try`() {
        // Nobody listens at this address: as without network.
        val freePort = ServerSocket(0).use { it.localPort }
        connectToObr("https://127.0.0.1:$freePort/ebms_api/")
        launch()
        openCustomers()
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)
        composeRule.typeInForm("Name", "Brasserie du Lac SA")

        composeRule.pressInForm("Create the customer")

        shown("The OBR could not be reached. Check the Internet connection and try again.")
        assertEquals(emptyList(), active())

        // The network is back.
        connectToObr()
        saveAndLeave("Create the customer")

        assertEquals(ObrSimulator.KNOWN_TIN, savedCustomer().details.tin?.number)
        composeRule.waitForText("Brasserie du Lac SA").assertIsDisplayed()
    }

    @Test
    fun `a server error of the OBR does not save the customer either`() {
        connectToObr()
        launch()
        openCustomers()
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Natural person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)

        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        composeRule.pressInForm("Create the customer")

        shown("The OBR server had an error. Try again later.")
        assertEquals(emptyList(), active())
    }

    @Test
    fun `without an EBMS connection a TIN cannot be checked`() {
        launch()
        openCustomers()
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Natural person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)

        composeRule.pressInForm("Check the TIN")

        shown("The EBMS connection is not configured: the TIN cannot be checked.")
        assertEquals(emptyList(), obrCalls())
    }

    @Test
    fun `an incomplete customer says what is missing and is not stored`() {
        launch()
        openCustomers()
        openNewCustomer()

        composeRule.pressInForm("Create the customer")
        shown("Choose whether the customer is local or foreign.")
        shown("Choose whether the customer is a natural or a legal person.")
        shown("Enter a name.")

        composeRule.pressInForm("Local")
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("Name", "Brasserie du Lac SA")
        composeRule.pressInForm("Create the customer")

        shown("Enter the TIN: a local legal person must have one.")
        assertEquals(emptyList(), active())
    }

    @Test
    fun `a foreign customer has no TIN and needs no network`() {
        launch()
        openCustomers()
        openNewCustomer()

        composeRule.pressInForm("Foreign")
        shown("A foreign customer has no TIN.")
        assertEquals(0, composeRule.onAllNodesWithTextCount("TIN"))
        assertEquals(0, composeRule.onAllNodesWithTextCount("Registered for the VAT"))
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("Name", "Kigali Trading Ltd")
        composeRule.typeInForm("Address", "Kigali, Rwanda")
        saveAndLeave("Create the customer")

        composeRule.waitForText("Kigali Trading Ltd").assertIsDisplayed()
        composeRule.onNodeWithText("Foreign · Legal person").assertIsDisplayed()
        val customer = active().single()
        assertEquals(CustomerOrigin.FOREIGN, customer.details.origin)
        assertNull(customer.details.tin)
        assertEquals("Kigali, Rwanda", customer.details.address)
    }

    @Test
    fun `a TIN belongs to one customer, and creating it again brings an archived customer back`() {
        connectToObr()
        val first = define(company)
        launch()
        openCustomers("1 active customer")
        openNewCustomer()
        composeRule.pressInForm("Local")
        composeRule.pressInForm("Legal person")
        composeRule.typeInForm("TIN", ObrSimulator.KNOWN_TIN)
        composeRule.typeInForm("Name", "Brasserie du Lac")

        composeRule.pressInForm("Create the customer")
        shown("Another customer already has this TIN.")
        assertEquals(listOf("Brasserie du Lac SA"), active().map { it.details.name })

        runBlocking { archiveCustomer(first.id, archived = true, actor = alice.id) }
        saveAndLeave("Create the customer")

        composeRule.waitForText("Brasserie du Lac").assertIsDisplayed()
        val restored = active().single()
        assertEquals(first.id, restored.id)
        assertEquals("Brasserie du Lac", restored.details.name)
        assertEquals(0, runBlocking { customers.count(archived = true) })
    }

    @Test
    fun `changing the address of a customer does not ask the OBR again`() {
        connectToObr()
        define(company)
        val callsBefore = obrCalls().size
        launch()
        openCustomers("1 active customer")
        composeRule.waitForText("Brasserie du Lac SA").performClick()
        composeRule.waitForText("Archive the customer")
        composeRule.waitForText("TIN checked on Oct 2, 2026", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.replaceInForm("Address", "Gitega")
        composeRule.pressInForm("Save")

        composeRule.waitForText("Anonymous sales")
        composeRule.waitUntil(TIMEOUT_MILLIS) { active().single().details.address == "Gitega" }
        assertEquals(callsBefore, obrCalls().size)
        assertEquals("CUSTOMER_UPDATED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `the search finds a customer by its name, its TIN or its phone, whatever the accents`() {
        connectToObr()
        define(company)
        define(walkIn)
        define(walkIn.copy(name = "Égide Niyonzima", phone = "68 000 111"))
        launch()
        openCustomers("3 active customers")
        composeRule.waitForText("Aline Irakoze")

        val search = "Search by name, TIN or phone"
        composeRule.onNodeWithText(search).performTextInput("egide")
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Aline Irakoze") == 0 }
        composeRule.onNodeWithText("Égide Niyonzima").assertIsDisplayed()
        // The counter customer is not a search result.
        assertEquals(0, composeRule.onAllNodesWithTextCount("Anonymous sales"))

        composeRule.onNodeWithText(search).performTextReplacement("0002020")
        composeRule.waitForText("Brasserie du Lac SA").assertIsDisplayed()

        composeRule.onNodeWithText(search).performTextReplacement("123 456")
        composeRule.waitForText("Aline Irakoze").assertIsDisplayed()

        composeRule.onNodeWithText(search).performTextReplacement("zzz")
        composeRule.waitForText("No customer matches this search.").assertIsDisplayed()
    }

    @Test
    fun `the name of the counter customer changes, and it cannot be archived`() {
        launch()
        openCustomers()
        composeRule.waitForText("Client comptoir").performClick()
        shown("This name is written on the invoices of the anonymous sales.")
        assertEquals(0, composeRule.onAllNodesWithTextCount("Archive the customer"))
        assertEquals(0, composeRule.onAllNodesWithTextCount("TIN"))

        composeRule.replaceInForm("Name", "Client de passage")
        saveAndLeave("Save")

        composeRule.waitForText("Client de passage").assertIsDisplayed()
        assertEquals("Client de passage", runBlocking { customers.counter() }.details.name)
    }

    @Test
    fun `an archived customer leaves the list and comes back from the archived customers`() {
        val customer = define(walkIn)
        launch()
        openCustomers("1 active customer")
        composeRule.waitForText("Aline Irakoze").performClick()
        composeRule.waitForText("Archive the customer")

        composeRule.pressInForm("Archive the customer")
        shown("This customer is archived: it is no longer proposed at the cash register.")
        assertEquals(true, runBlocking { customers.find(customer.id) }?.archived)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitForText("Every customer is archived.").assertIsDisplayed()
        composeRule.onNodeWithText("Show the archived customers").performClick()
        composeRule.waitForText("Aline Irakoze").performClick()
        composeRule.waitForText("Bring the customer back")
        composeRule.pressInForm("Bring the customer back")

        composeRule.waitForText("Archive the customer")
        assertEquals(false, runBlocking { customers.find(customer.id) }?.archived)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val EMPTY_MESSAGE = "No customer yet. Create the customers who want an invoice in their name: " +
            "the other sales go to the counter customer."
    }
}
