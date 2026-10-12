package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.DmcRepository
import com.claytonmuhoza.easybilling.domain.stock.ImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.obr.SimulatedDmc
import com.claytonmuhoza.easybilling.testing.obr.SimulatedDmcItem
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
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
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The imported goods (UC-F03, F04): a customs declaration is read from the OBR, one of its lines is
 * matched with an item of the catalogue and entered into the stock. The OBR is the simulated
 * server, over HTTPS. The administrator "Alice" is logged in unless a test says otherwise.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class ImportFlowTest {

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
    lateinit var createItem: CreateItem

    @Inject
    lateinit var stock: StockRepository

    @Inject
    lateinit var dmcs: DmcRepository

    @Inject
    lateinit var clock: TestClock

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var oil: Item
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun anImporterWithOilKeptInStock() {
        hiltRule.inject()
        simulator.dmcs[REFERENCE] = SimulatedDmc(
            TIN,
            listOf(
                SimulatedDmcItem("15119090000", "Huile de palme raffinee", "10", "Jerrycans"),
                SimulatedDmcItem("10063000000", "Riz blanchi", "250", "Sacs"),
            ),
        )
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
            val profile = TaxProfile(
                taxpayerType = TaxpayerType.LEGAL_PERSON,
                name = "Boutique Umuco SPRL",
                tin = TIN,
                tradeNumber = "RC 12345",
                postalNumber = "",
                phoneNumber = "+257 22 22 22 22",
                address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
                taxes = TaxRegistrations(),
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
            val form = ItemForm(
                code = "ART-0001",
                designation = "Huile de palme 20 L",
                unit = "bidon",
                price = "60000",
                priceMode = PriceMode.INCLUDING_TAXES,
                stockTracked = true,
            )
            oil = assertIs<CreateItem.Result.Created>(createItem(form, alice.id)).item
        }
    }

    @After
    fun stop() {
        if (::scenario.isInitialized) scenario.close()
        simulator.close()
    }

    private fun movements(): List<StockMovement> = runBlocking { stock.movementsOf(oil.id, limit = 10) }

    private fun statusOf(movement: StockMovement): MovementStatus? = runBlocking { stock.statusOf(movement.id) }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    /** Launches the application and opens the imported goods from the "Stock" tab. */
    private fun openImports() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Stock") > 0 }
        composeRule.onNode(hasText("Stock") and hasClickAction()).performClick()
        composeRule.waitForText("Imports").performClick()
        composeRule.waitForText("Read the declaration")
    }

    /** Reads the declaration from the OBR and opens the entry of its first line. */
    private fun openFirstLine() {
        openImports()
        composeRule.typeInForm("Reference of the declaration", REFERENCE)
        composeRule.pressInForm("Read the declaration")
        composeRule.waitForText("Huile de palme raffinee").performClick()
        composeRule.waitForText("Enter into the stock")
    }

    private fun typeEntry(quantity: String) {
        composeRule.pressInForm("Huile de palme 20 L")
        composeRule.typeInForm("Quantity (bidon)", quantity)
        composeRule.typeInForm("Unit purchase price in BIF, taxes included", "45000")
        composeRule.typeInForm("Pieces per packet", "1")
        composeRule.pressInForm("Enter into the stock")
    }

    @Test
    fun `a declaration is read from the OBR and one of its lines is entered into the stock`() {
        openImports()
        shown("No declaration has been read yet.")
        composeRule.typeInForm("Reference of the declaration", REFERENCE)
        composeRule.pressInForm("Read the declaration")

        composeRule.waitForText("Riz blanchi").assertIsDisplayed()
        shown("Declared: 10 · Entered: 0 · Left: 10")
        assertEquals(2, runBlocking { dmcs.find(REFERENCE) }?.lines?.size)
        composeRule.waitForText("Huile de palme raffinee").performClick()
        composeRule.waitForText("Enter into the stock")
        typeEntry(quantity = "4")

        // Back on the declaration, which tells what is left of the line.
        shown("Declared: 10 · Entered: 4 · Left: 6")
        shown("Entered as Huile de palme 20 L")
        val movement = movements().single()
        assertEquals(MovementType.EN, movement.content.type)
        assertEquals(Money.of("45000", Currency.BIF), movement.content.unitCost)
        assertEquals(
            ImportedGoods(REFERENCE, 1, "15119090000", BigDecimal("1"), "Jerrycans"),
            movement.content.imported,
        )
        assertEquals(0, BigDecimal("4").compareTo(runBlocking { stock.quantityOf(oil.id) }))
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(movement) == MovementStatus.Declared }
        val sent = simulator.importedMovements.single().mapValues { (it.value as JsonPrimitive).content }
        assertEquals(REFERENCE, sent["reference_dmc"])
        assertEquals("15119090000", sent["rubrique_tarifaire"])
        assertEquals("1", sent["nombre_par_paquet"])
        assertEquals("Jerrycans", sent["description_paquet"])
        assertEquals("EN", sent["item_movement_type"])
        assertEquals(emptyList(), simulator.stockMovements)
    }

    @Test
    fun `more than the declaration is refused, and what is missing is told`() {
        openFirstLine()
        composeRule.pressInForm("Enter into the stock")
        shown("Choose the item.")

        typeEntry(quantity = "10.5")

        shown("Only 10 is left to enter on this line of the declaration.")
        assertEquals(emptyList(), movements())
        assertEquals(emptyList(), simulator.importedMovements)
    }

    @Test
    fun `the declaration of another taxpayer shows the refusal of the OBR`() {
        openImports()
        composeRule.typeInForm("Reference of the declaration", "2025BIPORC0001")
        composeRule.pressInForm("Read the declaration")

        shown("The OBR refused: ${ObrSimulator.MESSAGE_DMC_OF_ANOTHER_TIN}")
        assertEquals(emptyList(), runBlocking { dmcs.all() })
    }

    @Test
    fun `a declaration already read is opened again without the OBR`() {
        openFirstLine()
        val calls = simulator.requests.size
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForText("Riz blanchi")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        shown("Declarations already read")
        composeRule.waitForText(REFERENCE).performClick()

        composeRule.waitForText("Riz blanchi").assertIsDisplayed()
        assertEquals(calls, simulator.requests.size)
    }

    @Test
    fun `a cashier does not reach the imported goods`() {
        val bob = runBlocking {
            val user = assertIs<CreateUser.Result.Created>(
                createUser(UserForm("Bob", setOf(Role.CASHIER)), "111111", "111111", alice.id),
            ).user
            changeTemporaryPin(user.id, "111111", "222222", "222222")
            user
        }
        runBlocking { sessions.open(bob) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Stock") > 0 }
        composeRule.onNode(hasText("Stock") and hasClickAction()).performClick()

        composeRule.waitForText("Huile de palme 20 L").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Imports"))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** The NIF and the reference of the example of page 33 of the specification. */
        const val TIN = "4000007155"
        const val REFERENCE = "2025BIPORC7090"
    }
}
