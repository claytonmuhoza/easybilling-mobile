package com.claytonmuhoza.easybilling

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.CreateTax
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.configuration.TaxForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRateForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.testing.IssuedInvoiceStatus
import com.claytonmuhoza.easybilling.testing.RecordingTransmissionScheduler
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.TestNetworkMonitor
import com.claytonmuhoza.easybilling.testing.backToHomeOnce
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressNewSale
import com.claytonmuhoza.easybilling.testing.waitForText
import com.claytonmuhoza.easybilling.transmission.TransmissionWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The transmission behind the screens, its banner and its screen (UC-D02, D03, D05), by the
 * administrator "Alice", already logged in, on a phone. The OBR is the simulated server, over
 * HTTPS; the network of the device and WorkManager are replaced by the ones of the tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class TransmissionFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var profiles: TaxProfileRepository

    @Inject
    lateinit var ebmsSettings: EbmsSettingsRepository

    @Inject
    lateinit var vault: EbmsCredentialsVault

    @Inject
    lateinit var saveNumbering: SaveNumbering

    @Inject
    lateinit var createTax: CreateTax

    @Inject
    lateinit var createItem: CreateItem

    @Inject
    lateinit var customers: CustomerRepository

    @Inject
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var outbox: OutboxRepository

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var network: TestNetworkMonitor

    @Inject
    lateinit var scheduler: RecordingTransmissionScheduler

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** Nobody listens at this address: as when the OBR cannot be reached. */
    private val unreachable = "https://127.0.0.1:${ServerSocket(0).use { it.localPort }}/ebms_api/"

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

    /** Points the test environment at [serverUrl], as saving the connection would. */
    private fun connectTo(serverUrl: String) = runBlocking {
        val settings = ebmsSettings.read()
        ebmsSettings.write(
            settings.copy(
                serverUrls = mapOf(EbmsEnvironment.TEST to serverUrl),
                lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to clock.now()),
            ),
        )
    }

    /** A business ready to sell soap at 1 000 without taxes, whose EBMS is at [serverUrl]. */
    private fun readyToSell(serverUrl: String) = runBlocking {
        val profile = TaxProfile(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            name = "Boutique Umuco SPRL",
            tin = "4000000001",
            tradeNumber = "RC 12345",
            postalNumber = "",
            phoneNumber = "+257 22 22 22 22",
            address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
            taxes = TaxRegistrations(vat = true),
            fiscalCenter = FiscalCenter.DMC,
            activitySector = "Commerce general",
            legalForm = "SPRL",
        )
        profiles.append(TaxProfileVersion(1, profile, clock.now(), alice.id))
        vault.write(EbmsEnvironment.TEST, EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD))
        connectTo(serverUrl)
        assertEquals(SaveNumbering.Result.Saved, saveNumbering(NumberingForm(), null, alice.id))
        val rate = TaxRateForm(TaxMode.PERCENTAGE, "18", validFrom = LocalDate.of(2026, 10, 2))
        val vat = assertIs<CreateTax.Result.Created>(createTax(TaxForm(TaxKind.VAT, "TVA 18 %", rate), alice.id)).tax
        val soap = ItemForm(
            code = "ART-0001",
            designation = "Savon de Marseille",
            unit = "piece",
            price = "1000",
            priceMode = PriceMode.EXCLUDING_TAXES,
            taxes = mapOf(TaxKind.VAT to vat.id),
            stockTracked = true,
        )
        assertIs<CreateItem.Result.Created>(createItem(soap, alice.id))
    }

    /**
     * Sells one soap from the home screen and waits for its invoice.
     *
     * @param customer the customer to choose, or null for the counter customer.
     * @return the issued invoice.
     */
    private fun sellOneSoap(customer: String? = null): Invoice {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.pressNewSale()
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("1 item · 1,180 BIF")
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.waitForText("Validate the sale")
        if (customer != null) {
            composeRule.pressInForm("Change")
            composeRule.waitForText("Anonymous sales")
            composeRule.waitForText(customer).performClick()
            composeRule.waitForText("Validate the sale")
            shown(customer)
        }
        composeRule.pressInForm("Validate the sale")
        composeRule.waitForText("Invoice FN-00001/2026")
        return runBlocking { invoices.findByNumber("FN-00001/2026") }!!
    }

    /** Sells one soap while the OBR cannot be reached, and waits for the first try to fail. */
    private fun sellOneSoapUnsent(): Invoice {
        readyToSell(unreachable)
        val invoice = sellOneSoap()
        waitForAttempts(1)
        return invoice
    }

    private fun waitForAttempts(attempts: Int) =
        composeRule.waitUntil(TIMEOUT_MILLIS) { pending().singleOrNull()?.attempts == attempts }

    private fun waitForRegistration(invoice: Invoice) =
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) == InvoiceStatus.Registered }

    private fun pending() = runBlocking { outbox.pending() }

    private fun statusOf(invoice: Invoice) = runBlocking { invoices.statusOf(invoice.id) }

    private fun shown(text: String, substring: Boolean = false) =
        composeRule.waitForText(text, substring).performScrollTo().assertIsDisplayed()

    private fun sentInvoices(): List<String> = simulator.requests
        .filter { it.url.encodedPath.endsWith("addInvoice_confirm/") }
        .map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject.getValue("invoice_number") }
        .map { (it as JsonPrimitive).content }

    /** Runs the planned pass as WorkManager would, the application doing nothing. */
    private fun runPlannedPass(): ListenableWorker.Result {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking { TestListenableWorkerBuilder<TransmissionWorker>(context).build().doWork() }
    }

    @Test
    fun `a sale made without network leaves by itself as soon as the network is back`() {
        network.set(false)
        val invoice = sellOneSoapUnsent()
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        // The next pass is planned for the end of the wait, for when the application is closed.
        assertEquals(Duration.ofSeconds(30), scheduler.plans.last())

        // The wait of 30 seconds is not over and nobody touches the screen.
        connectTo(simulator.baseUrl)
        network.set(true)

        waitForRegistration(invoice)
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
        assertEquals(emptyList(), pending())
        composeRule.waitUntil(TIMEOUT_MILLIS) { scheduler.plans.last() == null }
    }

    @Test
    fun `the planned pass sends the queue and plans nothing more once it is empty`() {
        val invoice = sellOneSoapUnsent()
        connectTo(simulator.baseUrl)
        clock.advance(Duration.ofSeconds(30))

        assertEquals(ListenableWorker.Result.success(), runPlannedPass())

        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
        assertEquals(emptyList(), pending())
        assertNull(scheduler.plans.last())
    }

    @Test
    fun `the planned pass plans the next one while the queue is not empty`() {
        val invoice = sellOneSoapUnsent()
        clock.advance(Duration.ofSeconds(30))
        val plansBefore = scheduler.plans.size

        assertEquals(ListenableWorker.Result.success(), runPlannedPass())

        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        assertEquals(2, pending().single().attempts)
        assertEquals(plansBefore + 1, scheduler.plans.size)
        val wait = assertNotNull(scheduler.plans.last())
        assertEquals(Duration.between(clock.now(), pending().single().nextAttemptAt), wait)
        assertEquals(emptyList(), simulator.requests)
    }

    @Test
    fun `the banner of the cash register opens the transmission, where the queue is sent without waiting`() {
        val invoice = sellOneSoapUnsent()

        // Selling goes on: the cash register only says that a document waits.
        composeRule.pressInForm("New sale")
        composeRule.waitForText("1 document waiting to be sent").performClick()

        composeRule.waitForText("OBR transmission")
        shown("Online")
        shown("1. FN-00001/2026 · Client comptoir")
        shown("Tries: 1", substring = true)
        composeRule.onNodeWithContentDescription("1 Waiting").assertExists()
        composeRule.onNodeWithContentDescription("0 Sent today").assertExists()

        // The wait of 30 seconds is not over: the button does not wait for it.
        connectTo(simulator.baseUrl)
        composeRule.pressInForm("Try again now")

        waitForRegistration(invoice)
        shown("Nothing to send.")
        composeRule.onNodeWithContentDescription("0 Waiting").assertExists()
        composeRule.onNodeWithContentDescription("1 Sent today").assertExists()
        composeRule.onNodeWithText("Try again now").assertIsNotEnabled()
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
    }

    @Test
    fun `the home screen says since when the device is offline and what waits`() {
        network.set(false)
        sellOneSoapUnsent()
        clock.advance(Duration.ofMinutes(14))

        composeRule.backToHomeOnce(IssuedInvoiceStatus.PENDING)

        shown("Offline")
        shown("1 document waiting to be sent, the oldest for 14 min")
        composeRule.onNodeWithText("Offline").performClick()
        composeRule.waitForText("OBR transmission")
        shown("Offline for 14 min")
        shown("The sale goes on. Documents leave in order as soon as the network is back.")
    }

    @Test
    fun `an invoice the OBR refused is announced on the home screen and opened from the transmission`() {
        readyToSell(simulator.baseUrl)
        runBlocking {
            customers.create(
                Customer.counterDetails("Inconnu SA").copy(
                    personType = TaxpayerType.LEGAL_PERSON,
                    tin = VerifiedTin("999", "INCONNU SA", clock.now()),
                    address = "Bujumbura, Rohero",
                    vatPayer = true,
                ),
            )
        }
        val invoice = sellOneSoap(customer = "Inconnu SA")
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) is InvoiceStatus.Rejected }

        composeRule.backToHomeOnce(IssuedInvoiceStatus.REJECTED)

        // Nothing waits, yet a refusal has to be dealt with.
        composeRule.waitForText("1 invoice rejected by the OBR").performScrollTo().performClick()
        composeRule.waitForText("OBR transmission")
        shown("To deal with")
        shown("FN-00001/2026 · Inconnu SA")
        shown("Reason given by the OBR: ${ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN}")
        shown("Nothing to send.")
        composeRule.onNodeWithContentDescription("1 Rejected").assertExists()

        composeRule.pressInForm("Open the invoice")

        // The detail of the invoice: the reason again, and the two ways out of a refusal.
        composeRule.waitForText("At the OBR")
        shown("Reason given by the OBR: ${ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN}")
        shown("Send again")
        shown("Start the sale over")
    }

    @Test
    fun `the home screen says that everything is synchronised when nothing waits`() {
        readyToSell(simulator.baseUrl)
        val invoice = sellOneSoap()
        waitForRegistration(invoice)

        composeRule.backToHomeOnce(IssuedInvoiceStatus.REGISTERED)

        shown("Synchronised with the OBR")
        shown("Nothing is waiting to be sent.")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
