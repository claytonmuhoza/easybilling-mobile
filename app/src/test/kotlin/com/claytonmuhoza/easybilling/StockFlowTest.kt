package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.InventoryRepository
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementForm
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.enterPin
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressKey
import com.claytonmuhoza.easybilling.testing.pressNewSale
import com.claytonmuhoza.easybilling.testing.typeInForm
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
 * The stock (UC-F01, F02, F05, F07, F10): its tab, the stock of an item, the entry and the exit
 * typed by a user and what the transmission screen asks a manager. The OBR is the simulated
 * server, over HTTPS. The administrator "Alice" is logged in unless a test says otherwise.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class StockFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var createUser: CreateUser

    @Inject
    lateinit var changeTemporaryPin: ChangeTemporaryPin

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
    lateinit var stock: StockRepository

    @Inject
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var inventory: InventoryRepository

    @Inject
    lateinit var settings: StockSettingsRepository

    @Inject
    lateinit var record: RecordStockMovement

    @Inject
    lateinit var clock: TestClock

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var soap: Item
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun aBusinessWithSoapKeptInStock() {
        hiltRule.inject()
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
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
            val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
            vault.write(EbmsEnvironment.TEST, credentials)
            ebmsSettings.write(
                ebmsSettings.read().copy(
                    serverUrls = mapOf(EbmsEnvironment.TEST to simulator.baseUrl),
                    lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to clock.now()),
                ),
            )
            assertEquals(SaveNumbering.Result.Saved, saveNumbering(NumberingForm(), null, alice.id))
            val rate = TaxRateForm(TaxMode.PERCENTAGE, "18", validFrom = LocalDate.of(2026, 10, 2))
            val created = createTax(TaxForm(TaxKind.VAT, "TVA 18 %", rate), alice.id)
            val vat = assertIs<CreateTax.Result.Created>(created).tax
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
    }

    @After
    fun stop() {
        if (::scenario.isInitialized) scenario.close()
        simulator.close()
    }

    /** Creates [name] with [role], who then chooses [pin], as Alice. */
    private fun account(name: String, role: Role, pin: String): User = runBlocking {
        val user = assertIs<CreateUser.Result.Created>(
            createUser(UserForm(name, setOf(role)), "111111", "111111", alice.id),
        ).user
        changeTemporaryPin(user.id, "111111", pin, pin)
        // Choosing the PIN opened a session: the session of the test comes back to Alice.
        sessions.open(alice)
        user
    }

    /** Alice enters [quantity] soaps bought at 800, without any screen. */
    private fun entered(quantity: String): StockMovement = runBlocking {
        val form = StockMovementForm(soap.id, MovementType.EN, quantity, unitCost = "800")
        assertIs<RecordStockMovement.Result.Recorded>(record(form, alice.id)).movement
    }

    private fun stockOfSoap(): BigDecimal = runBlocking { stock.quantityOf(soap.id) }

    private fun movements(): List<StockMovement> = runBlocking { stock.movementsOf(soap.id, limit = 10) }

    private fun statusOf(movement: StockMovement): MovementStatus? = runBlocking { stock.statusOf(movement.id) }

    /**
     * Waits for what the send queue makes of [movement]. The queue works off the main thread: a
     * change made by a screen is waited for on that screen first, which lets the main thread run.
     */
    private fun waitForStatus(movement: StockMovement, status: MovementStatus) {
        val reached = runCatching { composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(movement) == status } }
        val calls = simulator.requests.map { it.url.encodedPath.substringAfterLast("ebms_api/") }
        assertEquals(status, statusOf(movement), "Calls: $calls. ${reached.exceptionOrNull()?.message.orEmpty()}")
    }

    private fun tab(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    /** Launches the application and opens the stock of the soap from the "Stock" tab. */
    private fun openSoap() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Stock") > 0 }
        tab("Stock").performClick()
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("Movements")
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `an entry typed from the stock tab raises the stock and is declared to the OBR`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Stock") > 0 }
        tab("Stock").performClick()
        composeRule.waitForText("Savon de Marseille").assertIsDisplayed()
        composeRule.waitForText("0 piece").assertIsDisplayed()
        // A main screen is not left by an arrow.
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()

        composeRule.waitForText("Savon de Marseille").performClick()
        shown("No movement yet.")
        composeRule.pressInForm("Stock entry")
        composeRule.waitForText("Record the entry")
        composeRule.pressInForm("Local purchase")
        composeRule.typeInForm("Quantity", "12,5")
        composeRule.typeInForm("Unit purchase price in BIF, taxes included", "800")
        composeRule.typeInForm("Description", "Facture fournisseur F-88")
        composeRule.pressInForm("Record the entry")

        composeRule.waitForText("In stock: 12.5 piece")
        val movement = movements().single()
        assertEquals(MovementType.EN, movement.content.type)
        assertEquals("000001", movement.content.reference)
        assertEquals(alice.id, movement.content.recordedBy)
        assertEquals(0, BigDecimal("12.5").compareTo(stockOfSoap()))
        waitForStatus(movement, MovementStatus.Declared)
        shown("Declared to the OBR")
        val sent = simulator.stockMovements.single()
        assertEquals("EN", (sent["item_movement_type"] as JsonPrimitive).content)
        assertEquals("000001 Facture fournisseur F-88", (sent["item_movement_description"] as JsonPrimitive).content)
    }

    @Test
    fun `an exit needs a description and never takes more than the stock`() {
        val entry = entered("3")
        openSoap()

        composeRule.pressInForm("Stock exit")
        composeRule.waitForText("Record the exit")
        composeRule.pressInForm("Transfer out")
        composeRule.typeInForm("Quantity", "5")
        composeRule.pressInForm("Record the exit")
        shown("An exit needs a description.")

        composeRule.typeInForm("Description", "Vers le depot de Gitega")
        composeRule.pressInForm("Record the exit")
        shown("Only 3 piece in stock: an exit cannot take more.")

        assertEquals(listOf(entry), movements())
        assertEquals(0, BigDecimal("3").compareTo(stockOfSoap()))
    }

    @Test
    fun `a storekeeper records a loss with the PIN of a manager`() {
        entered("10")
        val marc = account("Marc", Role.MANAGER, pin = "333333")
        val sam = account("Sam", Role.STOREKEEPER, pin = "444444")
        runBlocking { sessions.open(sam) }
        openSoap()

        composeRule.pressInForm("Stock exit")
        composeRule.waitForText("Record the exit")
        composeRule.pressInForm("Loss")
        shown("A manager validates this exit.")
        composeRule.typeInForm("Quantity", "2")
        composeRule.typeInForm("Description", "Carton mouille")
        composeRule.pressInForm("Record the exit")
        composeRule.waitForText("Approval of a manager")
        assertEquals(1, movements().size)
        composeRule.waitForText("Marc").performClick()
        composeRule.enterPin("333333")
        composeRule.pressKey("Approve")

        composeRule.waitForText("In stock: 8 piece")
        val loss = movements().first()
        assertEquals(MovementType.SP, loss.content.type)
        assertEquals(sam.id, loss.content.recordedBy)
        assertEquals(marc.id, loss.content.approvedBy)
    }

    @Test
    fun `a cashier reads the stock and cannot move it`() {
        entered("4")
        val bob = account("Bob", Role.CASHIER, pin = "222222")
        runBlocking { sessions.open(bob) }
        openSoap()

        shown("In stock: 4 piece")
        shown("Local purchase")
        assertEquals(0, composeRule.onAllNodesWithTextCount("Stock entry"))
        assertEquals(0, composeRule.onAllNodesWithTextCount("Stock exit"))
    }

    @Test
    fun `a movement sent without an answer waits for a manager, who holds it as declared`() {
        simulator.loseNextMovementAnswer()
        val movement = entered("6")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForStatus(movement, MovementStatus.ToConfirm)
        // Nothing is sent again by itself: the server has the movement once.
        assertEquals(1, simulator.stockMovements.size)

        composeRule.waitForText("1 stock movement to deal with").performScrollTo().performClick()
        composeRule.waitForText("OBR transmission")
        shown("Stock movement 000001 · Savon de Marseille")
        shown("To confirm")
        composeRule.pressInForm("Hold as declared")

        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Hold as declared") == 0 }
        waitForStatus(movement, MovementStatus.Declared)
        assertEquals(1, simulator.stockMovements.size)
        assertNull(statusOf(movement)?.takeIf { it.toResolve })
    }

    @Test
    fun `a manager sends an uncertain movement again`() {
        simulator.loseNextMovementAnswer()
        val movement = entered("6")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForStatus(movement, MovementStatus.ToConfirm)

        composeRule.waitForText("1 stock movement to deal with").performScrollTo().performClick()
        composeRule.waitForText("OBR transmission")
        shown("Stock movement 000001 · Savon de Marseille")
        composeRule.pressInForm("Send again")

        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Send again") == 0 }
        waitForStatus(movement, MovementStatus.Declared)
        // The OBR cannot tell the two apart: it keeps both.
        assertEquals(2, simulator.stockMovements.size)
        assertEquals(0, BigDecimal("6").compareTo(stockOfSoap()))
    }

    /** Opens the cash register, adds [taps] soaps and opens the sale. */
    private fun typeSaleOfSoap(taps: Int, total: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.pressNewSale()
        repeat(taps) { tap ->
            composeRule.waitForText("Savon de Marseille").performClick()
            // The sale holds one more before the next touch.
            composeRule.waitForText("× ${tap + 1}")
        }
        composeRule.waitForText(total, substring = true)
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.waitForText("Validate the sale")
    }

    @Test
    fun `a sale takes its item out of the stock and declares the exit after the invoice`() {
        entered("5")
        typeSaleOfSoap(taps = 1, total = "1 item · 1,180 BIF")
        shown("In stock: 5 piece")

        composeRule.pressInForm("Validate the sale")
        composeRule.waitForText("Invoice FN-00001/2026")

        val invoice = runBlocking { invoices.findByNumber("FN-00001/2026") }!!
        val exit = runBlocking { stock.ofInvoice(invoice.id) }.single()
        assertEquals(MovementType.SN, exit.content.type)
        assertEquals(0, BigDecimal("4").compareTo(stockOfSoap()))
        waitForStatus(exit, MovementStatus.Declared)
        val calls = simulator.requests.map { it.url.encodedPath.substringAfterLast("ebms_api/") }
        assertEquals(listOf("AddStockMovement/", "addInvoice_confirm/", "AddStockMovement/"), calls - "login/")
        val sent = simulator.stockMovements.last().mapValues { (it.value as JsonPrimitive).content }
        assertEquals("SN", sent["item_movement_type"])
        assertEquals("1", sent["item_quantity"])
        assertEquals("1180", sent["item_cost_price"])
        assertEquals("000002 Facture FN-00001/2026", sent["item_movement_description"])
        assertEquals("", sent["item_movement_invoice_ref"])
    }

    @Test
    fun `the cash register does not sell more than the stock`() {
        entered("1")
        typeSaleOfSoap(taps = 2, total = "2,360 BIF")

        shown("Not enough in stock for this quantity.")
        shown("In stock: 1 piece")
        composeRule.onNodeWithText("Validate the sale").assertIsNotEnabled()
        assertNull(runBlocking { invoices.findByNumber("FN-00001/2026") })
        assertEquals(0, BigDecimal("1").compareTo(stockOfSoap()))
    }

    /** Opens the inventory from the "Stock" tab. */
    private fun openInventory() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Stock") > 0 }
        tab("Stock").performClick()
        composeRule.waitForText("Savon de Marseille")
        composeRule.onNodeWithText("Inventory").performClick()
        composeRule.waitForText("Close the inventory")
    }

    @Test
    fun `an inventory brings the stock to what was counted and declares the adjustment`() {
        entered("10")
        openInventory()
        shown("ART-0001 · Stock: 10 piece")

        composeRule.typeInForm("Counted quantity", "8")
        shown("Gap: −2 piece")
        // What is counted is kept, and moves nothing yet.
        assertEquals(listOf(BigDecimal("8")), runBlocking { inventory.counts() }.map { it.counted })
        assertEquals(0, BigDecimal("10").compareTo(stockOfSoap()))
        composeRule.typeInForm("Note", "Comptage du soir")
        composeRule.pressInForm("Close the inventory")

        composeRule.waitForText("Inventory closed: 1 adjustment recorded.")
        val adjustment = movements().first()
        assertEquals(MovementType.SAJ, adjustment.content.type)
        assertEquals(BigDecimal("2"), adjustment.content.quantity.value)
        assertEquals(0, BigDecimal("8").compareTo(stockOfSoap()))
        assertEquals(emptyList(), runBlocking { inventory.counts() })
        waitForStatus(adjustment, MovementStatus.Declared)
        val sent = simulator.stockMovements.last().mapValues { (it.value as JsonPrimitive).content }
        assertEquals("SAJ", sent["item_movement_type"])
        assertEquals("1180", sent["item_cost_price"])
        assertEquals(true, sent.getValue("item_movement_description").startsWith("000002 Inventaire du "))
        assertEquals(true, sent.getValue("item_movement_description").endsWith(" Comptage du soir"))
    }

    @Test
    fun `a storekeeper closes the inventory with the PIN of a manager`() {
        entered("10")
        val marc = account("Marc", Role.MANAGER, pin = "333333")
        val sam = account("Sam", Role.STOREKEEPER, pin = "444444")
        runBlocking { sessions.open(sam) }
        openInventory()

        composeRule.typeInForm("Counted quantity", "12")
        shown("Gap: +2 piece")
        shown("Empty: the last purchase price, 800 BIF")
        shown("A manager validates the closing of the inventory.")
        composeRule.pressInForm("Close the inventory")
        composeRule.waitForText("Approval of a manager")
        assertEquals(1, movements().size)
        composeRule.waitForText("Marc").performClick()
        composeRule.enterPin("333333")
        composeRule.pressKey("Approve")

        composeRule.waitForText("Inventory closed: 1 adjustment recorded.")
        val adjustment = movements().first().content
        assertEquals(MovementType.EAJ, adjustment.type)
        assertEquals(Money.of("800", Currency.BIF), adjustment.unitCost)
        assertEquals(sam.id, adjustment.recordedBy)
        assertEquals(marc.id, adjustment.approvedBy)
        assertEquals(0, BigDecimal("12").compareTo(stockOfSoap()))
    }

    @Test
    fun `an item at its reorder threshold is told to reorder, and the stock tab gives its value`() {
        entered("5")
        openSoap()

        composeRule.typeInForm("Reorder threshold", "5")
        composeRule.pressInForm("Save the threshold")
        shown("To reorder")
        assertEquals(mapOf(soap.id to BigDecimal("5")), runBlocking { settings.thresholds() })
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitForText("Only the items to reorder (1)")
        // Five soaps bought at 800.
        composeRule.waitForText("4,000 BIF").assertIsDisplayed()
        composeRule.waitForText("Value: 4,000 BIF").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
