package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.Item
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
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressNewSale
import com.claytonmuhoza.easybilling.testing.type
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * The list of the invoices, the detail of one, its check at the OBR and the ways out of a refusal
 * (UC-C08, C09, D04), by the administrator "Alice", already logged in, on a phone. The OBR is the
 * simulated server, over HTTPS.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class InvoicesFlowTest {

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
    lateinit var drafts: SaleDraftRepository

    @Inject
    lateinit var editSale: EditSale

    @Inject
    lateinit var issueSale: IssueSale

    @Inject
    lateinit var clock: TestClock

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var soap: Item
    private var scenario: ActivityScenario<MainActivity>? = null

    /** Nobody listens at this address: as when the OBR cannot be reached. */
    private val unreachable = "https://127.0.0.1:${ServerSocket(0).use { it.localPort }}/ebms_api/"

    private val profile = TaxProfile(
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
        scenario?.close()
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
        profiles.append(TaxProfileVersion(1, profile, clock.now(), alice.id))
        vault.write(EbmsEnvironment.TEST, EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD))
        connectTo(serverUrl)
        assertEquals(SaveNumbering.Result.Saved, saveNumbering(NumberingForm(), null, alice.id))
        val rate = TaxRateForm(TaxMode.PERCENTAGE, "18", validFrom = LocalDate.of(2026, 10, 2))
        val vat = assertIs<CreateTax.Result.Created>(createTax(TaxForm(TaxKind.VAT, "TVA 18 %", rate), alice.id)).tax
        val form = ItemForm(
            code = "ART-0001",
            designation = "Savon de Marseille",
            unit = "piece",
            price = "1000",
            priceMode = PriceMode.EXCLUDING_TAXES,
            taxes = mapOf(TaxKind.VAT to vat.id),
            stockTracked = true,
        )
        soap = assertIs<CreateItem.Result.Created>(createItem(form, alice.id)).item
    }

    /** The customer whose TIN the simulated OBR does not know: its invoices are refused. */
    private fun unknownCustomer() = runBlocking {
        customers.create(
            Customer.counterDetails("Inconnu SA").copy(
                personType = TaxpayerType.LEGAL_PERSON,
                tin = VerifiedTin("999", "INCONNU SA", clock.now()),
                address = "Bujumbura, Rohero",
                vatPayer = true,
            ),
        )
    }

    /**
     * Issues an invoice of one soap without the screens, and takes it out of the send queue: the
     * test then gives it the status it wants.
     */
    private fun issued(customer: Customer? = null, status: InvoiceStatus = InvoiceStatus.Registered): Invoice =
        runBlocking {
            editSale.addItem(soap.id)
            val draft = editSale.setCustomer(customer?.id)
            val invoice = assertIs<IssueSale.Result.Issued>(issueSale(draft.id, alice.id)).invoice
            outbox.pending().forEach { outbox.remove(it.id) }
            invoices.setStatus(invoice.id, status, clock.now())
            invoice
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

    /** Sells one soap to the customer the OBR does not know, and waits for the refusal. */
    private fun sellRejected(): Invoice {
        readyToSell(simulator.baseUrl)
        unknownCustomer()
        val invoice = sellOneSoap(customer = "Inconnu SA")
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) is InvoiceStatus.Rejected }
        return invoice
    }

    /** Goes from the screen of the issued invoice to the detail of [number], through the list. */
    private fun openFromHome(number: String) {
        composeRule.pressInForm("Back to home")
        openInvoices()
        composeRule.waitForText(number).performClick()
        composeRule.waitForText("At the OBR")
    }

    /** Opens the list by its tab, which does not move while the home screen loads. */
    private fun openInvoices() {
        composeRule.waitForText("Invoices").performClick()
        composeRule.waitForText("Number or customer")
    }

    private fun statusOf(invoice: Invoice) = runBlocking { invoices.statusOf(invoice.id) }

    private fun shown(text: String, substring: Boolean = false) =
        composeRule.waitForText(text, substring).performScrollTo().assertIsDisplayed()

    /** The filters, above the list, show [texts]. */
    private fun filters(vararg texts: String) = texts.forEach { composeRule.waitForText(it).assertExists() }

    /** The list holds [texts]; it is scrolled to each, since it only draws the rows on screen. */
    private fun listed(vararg texts: String) = texts.forEach { text ->
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runCatching { composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)) }.isSuccess
        }
    }

    private fun notListed(vararg texts: String) = texts.forEach { text ->
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount(text) == 0 }
    }

    private fun pressChip(label: String) = composeRule.onNode(hasText(label) and hasClickAction()).performClick()

    private fun sentInvoices(): List<JsonObject> = simulator.requests
        .filter { it.url.encodedPath.endsWith("addInvoice_confirm/") }
        .map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject }

    private fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content

    @Test
    fun `the invoices are listed by day, searched and filtered by status and by period`() {
        readyToSell(unreachable)
        val kaneza = runBlocking { customers.create(Customer.counterDetails("Ets Kaneza")) }
        issued()
        // Two days later; the session of Alice ended meanwhile, she logs in again.
        clock.advance(Duration.ofDays(2))
        runBlocking { sessions.open(alice) }
        issued(customer = kaneza)
        issued(status = InvoiceStatus.Rejected("Le NIF du contribuable est invalide."))

        scenario = ActivityScenario.launch(MainActivity::class.java)
        openInvoices()

        // The most recent day first, each with its number of invoices and its total without the refused one.
        filters("All 3", "Pending 0", "Registered 2", "Rejected 1", "Cancelled 0")
        listed("2 invoices · 1,180 BIF", "Oct 2, 2026", "1 invoice · 1,180 BIF")
        listed("FN-00001/2026", "FN-00002/2026", "FN-00003/2026", "Le NIF du contribuable est invalide.")

        pressChip("Rejected 1")
        notListed("FN-00001/2026", "FN-00002/2026")
        listed("FN-00003/2026")
        filters("All 3")

        pressChip("All 3")
        composeRule.type("Number or customer", "kaneza")
        notListed("FN-00001/2026", "FN-00003/2026")
        listed("FN-00002/2026")
        filters("All 1", "Registered 1", "Rejected 0")

        composeRule.onNodeWithText("Number or customer").performTextClearance()
        composeRule.type("Number or customer", "introuvable")
        listed("No invoice matches.")
        filters("All 0")

        composeRule.onNodeWithText("Number or customer").performTextClearance()
        listed("FN-00001/2026")
        pressChip("Today")
        notListed("FN-00001/2026", "Oct 2, 2026")
        listed("FN-00002/2026", "FN-00003/2026")
        filters("All 2")
    }

    @Test
    fun `without any invoice the list says so`() {
        readyToSell(unreachable)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Invoices").performClick()

        composeRule.waitForText("No invoice yet").assertIsDisplayed()
        composeRule.onNodeWithText("The invoices you issue are listed here.").assertIsDisplayed()
    }

    @Test
    fun `a registered invoice is checked at the OBR, and a difference changes nothing on the device`() {
        readyToSell(simulator.baseUrl)
        val invoice = sellOneSoap()
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) == InvoiceStatus.Registered }

        openFromHome("FN-00001/2026")

        shown("Registered")
        shown("Client comptoir")
        shown("OBR registration number")
        shown(invoice.content.identifier)
        composeRule.pressInForm("Check at the OBR")
        shown("The OBR has this invoice, as it is on this device.")
        shown("Number at the OBR")
        shown("Cancelled at the OBR")
        assertEquals("/ebms_api/getInvoice/", simulator.requests.last().url.encodedPath)

        simulator.markCancelled(invoice.content.identifier)
        composeRule.pressInForm("Check at the OBR")
        shown("The OBR has this invoice, with differences. Nothing was changed on this device.")
        shown("The cancellation is not the same on both sides.")
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))

        connectTo(unreachable)
        composeRule.pressInForm("Check at the OBR")
        shown("The OBR could not be reached. Nothing is concluded: try again later.")
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
    }

    @Test
    fun `an invoice that has not left yet is unknown at the OBR, and stays pending`() {
        readyToSell(unreachable)
        val invoice = sellOneSoap()
        composeRule.waitUntil(TIMEOUT_MILLIS) { runBlocking { outbox.pending() }.singleOrNull()?.attempts == 1 }
        connectTo(simulator.baseUrl)

        openFromHome("FN-00001/2026")

        shown("Pending")
        shown("This invoice leaves by itself as soon as the OBR can be reached.")
        shown("The OBR has not acknowledged this invoice.")
        composeRule.pressInForm("Check at the OBR")
        shown("The OBR does not know this invoice.")
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        assertEquals(emptyList(), sentInvoices())
    }

    @Test
    fun `a rejected invoice is sent again with the corrected tax profile, and registered`() {
        val invoice = sellRejected()
        // The cause is corrected: the OBR now knows the customer, and the NIF of the seller changed.
        simulator.taxpayers["999"] = "INCONNU SA"
        runBlocking { profiles.append(TaxProfileVersion(2, profile.copy(tin = "4000000002"), clock.now(), alice.id)) }

        openFromHome("FN-00001/2026")
        shown("Rejected")
        shown("Reason given by the OBR: ${ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN}")
        composeRule.pressInForm("Send again")

        // The screen first: the storage is not read from here while the resend is being written.
        shown("The invoice is back in the send queue.")
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) == InvoiceStatus.Registered }
        shown("Registered")
        shown("OBR registration number")
        val resent = sentInvoices().last()
        assertEquals(2, sentInvoices().size)
        assertEquals("FN-00001/2026", resent.text("invoice_number"))
        assertEquals("4000000002", resent.text("tp_TIN"))
        assertEquals(invoice.content.identifier.replaceBefore('/', "4000000002"), resent.text("invoice_identifier"))
        assertEquals(sentInvoices().first().text("invoice_date"), resent.text("invoice_date"))
        assertEquals(1, runBlocking { invoices.count() })
    }

    @Test
    fun `the sale of a rejected invoice is started over, and the invoice stays rejected`() {
        val invoice = sellRejected()

        openFromHome("FN-00001/2026")
        composeRule.pressInForm("Start the sale over")

        // The cash register shows the new sale, with the line of the invoice.
        composeRule.waitForText("1 item · 1,180 BIF").assertIsDisplayed()
        val started = assertNotNull(runBlocking { drafts.current() })
        assertEquals(listOf(soap.id), started.lines.map { it.itemId })
        assertEquals(invoice.content.customer.id, started.customerId)
        assertIs<InvoiceStatus.Rejected>(statusOf(invoice))
        assertEquals(1, runBlocking { invoices.count() })
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
