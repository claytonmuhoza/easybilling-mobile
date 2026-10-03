package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
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
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
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
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
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
import java.net.ServerSocket
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The cash register and the transmission of its invoices (UC-C01, C02, C03, C07), by the
 * administrator "Alice", already logged in, on a phone: the catalogue and the sale are shown one at
 * a time. The OBR is the simulated server, over HTTPS: no test ever calls the real one.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class SaleFlowTest {

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
    lateinit var drafts: SaleDraftRepository

    @Inject
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var outbox: OutboxRepository

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var receipts: ReceiptArchive

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vat: TaxDefinition

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

    private fun saveProfile(vatTaxpayer: Boolean, version: Int = 1) = runBlocking {
        val profile = TaxProfile(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            name = "Boutique Umuco SPRL",
            tin = "4000000001",
            tradeNumber = "RC 12345",
            postalNumber = "",
            phoneNumber = "+257 22 22 22 22",
            address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
            taxes = TaxRegistrations(vat = vatTaxpayer),
            fiscalCenter = FiscalCenter.DMC,
            activitySector = "Commerce general",
            legalForm = "SPRL",
        )
        profiles.append(TaxProfileVersion(version, profile, clock.now(), alice.id))
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

    /**
     * Finishes the configuration: connection to the simulated EBMS tested, numbering saved, VAT of
     * 18 %.
     */
    private fun finishConfiguration(serverUrl: String = simulator.baseUrl) = runBlocking {
        val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        vault.write(EbmsEnvironment.TEST, credentials)
        connectTo(serverUrl)
        assertEquals(SaveNumbering.Result.Saved, saveNumbering(NumberingForm(), null, alice.id))
        val rate = TaxRateForm(TaxMode.PERCENTAGE, "18", validFrom = LocalDate.of(2026, 10, 2))
        vat = assertIs<CreateTax.Result.Created>(createTax(TaxForm(TaxKind.VAT, "TVA 18 %", rate), alice.id)).tax
    }

    private fun item(code: String, designation: String, price: String, mode: PriceMode, barcode: String = ""): Item =
        runBlocking {
            val form = ItemForm(
                code = code,
                designation = designation,
                unit = "piece",
                price = price,
                priceMode = mode,
                taxes = mapOf(TaxKind.VAT to vat.id),
                stockTracked = true,
                barcode = barcode,
            )
            assertIs<CreateItem.Result.Created>(createItem(form, alice.id)).item
        }

    /** A business ready to sell soap at 1 000 without taxes and sugar at 2 360 taxes included. */
    private fun readyToSell(serverUrl: String = simulator.baseUrl) {
        saveProfile(vatTaxpayer = true)
        finishConfiguration(serverUrl)
        item("ART-0001", "Savon de Marseille", "1000", PriceMode.EXCLUDING_TAXES, barcode = "6001234567890")
        item("ART-0002", "Sucre 1 kg", "2360", PriceMode.INCLUDING_TAXES)
    }

    /** @param status what the home screen says of the sales, the last status it loads. */
    private fun openRegister(status: String = "Open the cash register") {
        composeRule.waitForText(status)
        composeRule.pressInForm("New sale")
    }

    /** Touches an item of the catalogue, and waits for the sale to hold [inSale] of it. */
    private fun add(designation: String, inSale: String) {
        composeRule.waitForText(designation).performClick()
        composeRule.waitForText("× $inSale")
    }

    /** Goes from the catalogue to the sale once the cart bar shows [summary], such as "1 item · 1,180 BIF". */
    private fun viewSale(summary: String) {
        composeRule.waitForText(summary).assertIsDisplayed()
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.waitForText("Validate the sale")
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    private fun pressDescribed(description: String) =
        composeRule.onNodeWithContentDescription(description).performScrollTo().performClick()

    private fun issuedInvoice(number: String = "FN-00001/2026"): Invoice {
        composeRule.waitForText("Invoice $number").assertIsDisplayed()
        return runBlocking { invoices.findByNumber(number) }!!
    }

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun statusOf(invoice: Invoice) = runBlocking { invoices.statusOf(invoice.id) }

    private fun sentInvoices(): List<String> = simulator.requests
        .filter { it.url.encodedPath.endsWith("addInvoice_confirm/") }
        .map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject.getValue("invoice_number") }
        .map { (it as JsonPrimitive).content }

    /** Stores a customer whose TIN was "recognised" as [tin], without asking the simulated OBR. */
    private fun customerWithTin(name: String, tin: String): Customer = runBlocking {
        customers.create(
            Customer.counterDetails(name).copy(
                personType = TaxpayerType.LEGAL_PERSON,
                tin = VerifiedTin(tin, name.uppercase(), clock.now()),
                address = "Bujumbura, Rohero",
                vatPayer = true,
            ),
        )
    }

    /** Chooses [name] as the customer of the sale, from the sale. */
    private fun chooseCustomer(name: String) {
        composeRule.pressInForm("Change")
        composeRule.waitForText("Anonymous sales")
        composeRule.waitForText(name).performClick()
        composeRule.waitForText("Validate the sale")
        shown(name)
    }

    @Test
    fun `the cash register stays locked until the configuration is complete`() {
        saveProfile(vatTaxpayer = true)
        launch()
        openRegister()

        composeRule.waitForText("Cash register locked").assertIsDisplayed()
        composeRule.onNodeWithText("Finish the settings marked “To do” on the home screen before selling.")
            .assertIsDisplayed()
        assertNull(runBlocking { drafts.current() })
    }

    @Test
    fun `a sale at the counter is issued, sent to the OBR and registered with its acknowledgement`() {
        readyToSell()
        launch()
        openRegister()

        add("Savon de Marseille", inSale = "1")
        add("Savon de Marseille", inSale = "2")
        add("Sucre 1 kg", inSale = "1")
        viewSale("2 items · 4,720 BIF")
        shown("2 × 1,000 BIF")
        shown("Client comptoir")
        composeRule.pressInForm("Validate the sale")

        val invoice = issuedInvoice()
        composeRule.waitForText("Registered by the OBR").assertIsDisplayed()
        shown("4530253")
        composeRule.onNodeWithText("4000000001/ws400000000100001/20261002100000/FN-00001/2026").assertExists()
        assertEquals(bif("4720"), invoice.content.totals.total)
        assertEquals(bif("720"), invoice.content.totals.vat)
        assertEquals("Client comptoir", invoice.content.customer.name)
        assertEquals(PaymentType.CASH, invoice.content.paymentType)
        assertEquals(listOf("Savon de Marseille", "Sucre 1 kg"), invoice.content.lines.map { it.designation })
        assertEquals(InvoiceStatus.Registered, statusOf(invoice))
        assertEquals(emptyList(), runBlocking { outbox.pending() })
        assertEquals("4530253", runBlocking { receipts.find(invoice.id) }?.registeredNumber)
        val audited = runBlocking { auditStore.readAll() }.map { it.action }
        assertEquals(listOf("INVOICE_ISSUED", "INVOICE_REGISTERED"), audited.takeLast(2))
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
        assertNull(runBlocking { drafts.current() })

        composeRule.pressInForm("New sale")
        add("Sucre 1 kg", inSale = "1")
        viewSale("1 item · 2,360 BIF")
        composeRule.pressInForm("Validate the sale")
        assertEquals(bif("2360"), issuedInvoice("FN-00002/2026").content.totals.total)
        composeRule.waitForText("Registered by the OBR")
        assertEquals(listOf("FN-00001/2026", "FN-00002/2026"), sentInvoices())
    }

    @Test
    fun `a sale made while the OBR cannot be reached waits, then is sent once at the next try`() {
        // Nobody listens at this address: as without network.
        val freePort = ServerSocket(0).use { it.localPort }
        readyToSell(serverUrl = "https://127.0.0.1:$freePort/ebms_api/")
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")
        composeRule.pressInForm("Validate the sale")

        // The sale is not held back by the network.
        val invoice = issuedInvoice()
        composeRule.waitForText("Awaiting transmission to the OBR").assertIsDisplayed()
        composeRule.waitUntil(TIMEOUT_MILLIS) { runBlocking { outbox.pending() }.singleOrNull()?.attempts == 1 }
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))
        assertEquals(OutboxOperation.SendInvoice(invoice.id), runBlocking { outbox.pending() }.single().operation)
        assertEquals(emptyList(), simulator.requests)

        // The network is back and the wait of 30 seconds is over: the home screen tries again.
        connectTo(simulator.baseUrl)
        clock.advance(Duration.ofSeconds(30))
        composeRule.pressInForm("Back to home")

        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) == InvoiceStatus.Registered }
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
        assertEquals(emptyList(), runBlocking { outbox.pending() })
    }

    @Test
    fun `an invoice whose acknowledgement is lost is found at the OBR instead of being sent twice`() {
        readyToSell()
        simulator.loseNextAcknowledgement()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")
        composeRule.pressInForm("Validate the sale")

        val invoice = issuedInvoice()
        composeRule.waitUntil(TIMEOUT_MILLIS) { runBlocking { outbox.pending() }.singleOrNull()?.uncertain == true }
        assertEquals(InvoiceStatus.Pending, statusOf(invoice))

        clock.advance(Duration.ofSeconds(30))
        composeRule.pressInForm("Back to home")

        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(invoice) == InvoiceStatus.Registered }
        assertEquals(listOf("FN-00001/2026"), sentInvoices())
        assertEquals(setOf(invoice.content.identifier), simulator.invoices.keys)
        // The acknowledgement was lost with the answer.
        assertNull(runBlocking { receipts.find(invoice.id) })
    }

    @Test
    fun `an invoice the OBR refuses shows the message of the OBR and leaves the queue`() {
        readyToSell()
        customerWithTin("Inconnu SA", tin = "999")
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")
        chooseCustomer("Inconnu SA")
        composeRule.pressInForm("Validate the sale")

        val invoice = issuedInvoice()
        shown("Rejected by the OBR: ${ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN}")
        assertEquals(InvoiceStatus.Rejected(ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN), statusOf(invoice))
        assertEquals(emptyList(), runBlocking { outbox.pending() })
        assertEquals(emptyMap(), simulator.invoices)
        assertEquals("INVOICE_REJECTED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `a sale to a customer registered for the VAT carries its TIN to the OBR`() {
        readyToSell()
        customerWithTin("Brasserie du Lac SA", tin = ObrSimulator.KNOWN_TIN)
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")
        chooseCustomer("Brasserie du Lac SA")
        composeRule.pressInForm("Validate the sale")

        issuedInvoice()
        composeRule.waitForText("Registered by the OBR").assertIsDisplayed()
        val sent = simulator.invoices.values.single()
        assertEquals("Brasserie du Lac SA", (sent["customer_name"] as JsonPrimitive).content)
        assertEquals(ObrSimulator.KNOWN_TIN, (sent["customer_TIN"] as JsonPrimitive).content)
        assertEquals("1", (sent["vat_customer_payer"] as JsonPrimitive).content)
    }

    @Test
    fun `the quantity takes decimals and a discount lowers the price of the line`() {
        readyToSell()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")

        pressDescribed("Modify: Savon de Marseille")
        composeRule.replaceInForm("Quantity", "0")
        composeRule.pressInForm("Apply")
        shown("Enter a quantity above zero, with two decimals at most.")

        composeRule.replaceInForm("Quantity", "2,5")
        composeRule.pressInForm("Percent")
        composeRule.typeInForm("Discount in percent", "10")
        composeRule.pressInForm("Apply")

        shown("2.5 × 900 BIF")
        shown("Discount 10 %")
        composeRule.pressInForm("Validate the sale")

        val line = issuedInvoice().content.lines.single()
        assertEquals(Quantity.of("2.5"), line.quantity)
        assertEquals(Discount.Percent(BigDecimal("10")), line.discount)
        assertEquals(bif("1000"), line.listUnitPrice)
        assertEquals(bif("900"), line.amounts.unitPrice)
        assertEquals(bif("2655"), line.amounts.total)
    }

    @Test
    fun `a discount in amount applies to a price including taxes`() {
        readyToSell()
        launch()
        openRegister()
        add("Sucre 1 kg", inSale = "1")
        viewSale("1 item · 2,360 BIF")

        pressDescribed("Modify: Sucre 1 kg")
        composeRule.pressInForm("Amount")
        composeRule.typeInForm("Discount per unit in BIF", "360")
        composeRule.pressInForm("Apply")

        shown("Discount 360 BIF per unit")
        composeRule.pressInForm("Validate the sale")
        assertEquals(bif("2000"), issuedInvoice().content.totals.total)
    }

    @Test
    fun `the buttons of a line add one, take one off and empty the sale`() {
        readyToSell()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")

        pressDescribed("One more: Savon de Marseille")
        shown("2 × 1,000 BIF")
        pressDescribed("One less: Savon de Marseille")
        shown("1 × 1,000 BIF")
        pressDescribed("One less: Savon de Marseille")

        shown("No item in this sale yet.")
        composeRule.onNodeWithText("Validate the sale").assertIsNotEnabled()
        composeRule.onNodeWithText("Put on hold").assertIsNotEnabled()
        assertEquals(0, runBlocking { invoices.count() })
    }

    @Test
    fun `a sale put on hold leaves room for another one, then is resumed or deleted`() {
        readyToSell()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")

        composeRule.pressInForm("Put on hold")

        composeRule.waitForText("On hold (1)")
        assertEquals(0, composeRule.onAllNodesWithTextCount("× 1"))
        add("Sucre 1 kg", inSale = "1")
        composeRule.onNodeWithText("On hold (1)").performClick()
        composeRule.waitForText("Sales on hold")
        composeRule.waitForText("1 item · 1,180 BIF").assertIsDisplayed()

        composeRule.onNodeWithText("Resume").performClick()

        // The soap is back at the cash register, and the sugar took its place on hold.
        composeRule.waitForText("1 item · 1,180 BIF")
        composeRule.waitForText("On hold (1)").performClick()
        composeRule.waitForText("1 item · 2,360 BIF").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.waitForText("No sale on hold").assertIsDisplayed()
        assertEquals(emptyList(), runBlocking { drafts.held() })
        assertEquals(1, runBlocking { drafts.current() }!!.lines.size)
        assertEquals(0, runBlocking { invoices.count() })
    }

    @Test
    fun `a customer that does not exist yet is created from the sale and chosen at once`() {
        readyToSell()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")

        composeRule.pressInForm("Change")
        composeRule.waitForText("Customer of the sale")
        composeRule.onNodeWithText("New customer").performClick()
        composeRule.waitForText("Create the customer")
        composeRule.pressInForm("Foreign")
        composeRule.pressInForm("Natural person")
        composeRule.typeInForm("Name", "John Smith")
        composeRule.pressInForm("Create the customer")

        // Back at the sale, without passing by the list again.
        composeRule.waitForText("Validate the sale")
        shown("John Smith")
        composeRule.pressInForm("Credit")
        // The button comes back once the choice is stored.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText("Validate the sale") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                runBlocking { drafts.current() }?.paymentType == PaymentType.CREDIT
        }
        composeRule.pressInForm("Validate the sale")

        val invoice = issuedInvoice().content
        assertEquals("John Smith", invoice.customer.name)
        assertEquals(PaymentType.CREDIT, invoice.paymentType)
        val created = runBlocking { customers.search("john", archived = false, limit = 10) }.single()
        assertEquals(created.id, invoice.customer.id)
    }

    @Test
    fun `an existing customer is found and chosen, and the counter customer chosen back`() {
        readyToSell()
        runBlocking { customers.create(Customer.counterDetails("Aline Irakoze")) }
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        viewSale("1 item · 1,180 BIF")

        composeRule.pressInForm("Change")
        composeRule.waitForText("Anonymous sales")
        composeRule.onNodeWithText("Search by name, TIN or phone").performTextInput("aline")
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Anonymous sales") == 0 }
        composeRule.waitForText("Aline Irakoze").performClick()

        composeRule.waitForText("Validate the sale")
        shown("Aline Irakoze")
        assertEquals("Aline Irakoze", runBlocking { customers.find(drafts.current()!!.customerId!!) }!!.details.name)

        composeRule.pressInForm("Change")
        composeRule.waitForText("Anonymous sales").performClick()
        composeRule.waitForText("Validate the sale")
        shown("Client comptoir")
        assertNull(runBlocking { drafts.current() }!!.customerId)
    }

    @Test
    fun `an item without VAT cannot be sold by a business registered for the VAT`() {
        saveProfile(vatTaxpayer = false)
        runBlocking {
            val old = ItemForm("ART-0009", "Ancien article", "piece", "500", PriceMode.EXCLUDING_TAXES)
            assertIs<CreateItem.Result.Created>(createItem(old.copy(stockTracked = true), alice.id))
        }
        saveProfile(vatTaxpayer = true, version = 2)
        finishConfiguration()
        launch()
        openRegister()
        add("Ancien article", inSale = "1")
        viewSale("1 item · 500 BIF")

        shown("This item has no VAT: complete it in the catalogue.")
        composeRule.onNodeWithText("Validate the sale").assertIsNotEnabled()
    }

    @Test
    fun `a barcode typed by a reader adds the item and empties the search`() {
        readyToSell()
        launch()
        openRegister()
        composeRule.waitForText("Savon de Marseille")

        val search = composeRule.onNodeWithText("Search an item or scan a barcode")
        search.performTextInput("6001234567890")
        search.performImeAction()

        composeRule.waitForText("× 1")
        composeRule.waitForText("Sucre 1 kg").assertIsDisplayed()
        composeRule.waitForText("1 item · 1,180 BIF").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("6001234567890", substring = true))
    }

    @Test
    fun `the sale being typed survives when the cash register is left`() {
        readyToSell()
        launch()
        openRegister()
        add("Savon de Marseille", inSale = "1")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        openRegister()

        composeRule.waitForText("1 item · 1,180 BIF").assertIsDisplayed()
        composeRule.onNodeWithText("× 1").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
