package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.CreateTax
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
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.testing.TestClock
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
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The items of the catalogue (UC-B01, B04, B07), by the administrator "Alice", already logged in. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class ItemsFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var items: ItemRepository

    @Inject
    lateinit var createItem: CreateItem

    @Inject
    lateinit var createTax: CreateTax

    @Inject
    lateinit var saveNumbering: SaveNumbering

    @Inject
    lateinit var profiles: TaxProfileRepository

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val soap = ItemForm(
        code = "ART-0001",
        designation = "Savon de Marseille",
        unit = "piece",
        price = "1500",
        priceMode = PriceMode.INCLUDING_TAXES,
        stockTracked = true,
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
    fun closeActivity() = scenario.close()

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun define(form: ItemForm): Item = runBlocking {
        assertIs<CreateItem.Result.Created>(createItem(form, alice.id)).item
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

    private fun defineVat() = runBlocking {
        val rate = TaxRateForm(TaxMode.PERCENTAGE, "18", validFrom = LocalDate.of(2026, 10, 2))
        assertIs<CreateTax.Result.Created>(createTax(TaxForm(TaxKind.VAT, "TVA 18 %", rate), alice.id)).tax
    }

    /** @param status what the home screen says of the items, the last status it loads. */
    private fun openItems(status: String = "No item") {
        composeRule.waitForText(status)
        composeRule.pressInForm("Items")
    }

    private fun openNewItem() {
        composeRule.waitForText("Search by code, designation or barcode", substring = true)
        composeRule.onAllNodesWithText("New item").onFirst().performClick()
        composeRule.waitForText("Create the item")
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    private fun stored(code: String = "ART-0001"): Item? = runBlocking { items.findByCode(code) }

    @Test
    fun `the first item is created from the empty catalogue with a suggested code`() {
        launch()
        openItems()
        composeRule.waitForText("No item yet")
        composeRule.onAllNodesWithText("New item").onFirst().performClick()
        composeRule.waitForText("Create the item")

        composeRule.typeInForm("Designation", "Savon de Marseille")
        composeRule.typeInForm("Measurement unit", "piece")
        composeRule.typeInForm("Unit price in BIF", "1500")
        composeRule.pressInForm("Taxes included")
        composeRule.pressInForm("Kept in stock")
        composeRule.pressInForm("Create the item")

        composeRule.waitForText("Savon de Marseille").assertIsDisplayed()
        composeRule.onNodeWithText("ART-0001 · 1,500 BIF incl. taxes / piece", substring = true).assertIsDisplayed()
        val item = stored()!!
        assertEquals("Savon de Marseille", item.details.designation)
        assertEquals(Money.of("1500", Currency.BIF), item.details.price)
        assertEquals(PriceMode.INCLUDING_TAXES, item.details.priceMode)
        assertEquals(true, item.details.stockTracked)
        assertEquals("ITEM_CREATED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `an incomplete item says what is missing and is not stored`() {
        define(soap)
        launch()
        openItems("1 active item")
        openNewItem()

        composeRule.pressInForm("Create the item")

        shown("Enter a designation.")
        shown("Enter a unit.")
        shown("Enter a price.")
        shown("Choose whether the price includes the taxes.")
        shown("Choose whether the item is kept in stock.")
        assertEquals(1, runBlocking { items.count(archived = false) })
    }

    @Test
    fun `the code proposed follows the catalogue and a code already used is refused`() {
        define(soap)
        launch()
        openItems("1 active item")
        openNewItem()

        composeRule.waitForText("ART-0002")
        composeRule.replaceInForm("Code", "art-0001")
        composeRule.typeInForm("Designation", "Savon noir")
        composeRule.typeInForm("Measurement unit", "piece")
        composeRule.typeInForm("Unit price in BIF", "900")
        composeRule.pressInForm("Without taxes")
        composeRule.pressInForm("Not kept in stock")
        composeRule.pressInForm("Create the item")

        shown("Another item already has this code.")
        assertEquals(1, runBlocking { items.count(archived = false) })
    }

    @Test
    fun `with several devices the code starts with the code of the device`() {
        runBlocking {
            val numbering = NumberingForm.defaultFor(multipleDevices = true).copy(deviceCode = "A1")
            assertEquals(SaveNumbering.Result.Saved, saveNumbering(numbering, null, alice.id))
        }
        define(soap.copy(code = "A1-ART-0001"))
        launch()
        openItems("1 active item")
        openNewItem()

        composeRule.waitForText("A1-ART-0002")
        composeRule.replaceInForm("Code", "ART-0002")
        composeRule.typeInForm("Designation", "Savon noir")
        composeRule.typeInForm("Measurement unit", "piece")
        composeRule.typeInForm("Unit price in BIF", "900")
        composeRule.pressInForm("Without taxes")
        composeRule.pressInForm("Kept in stock")
        composeRule.pressInForm("Create the item")

        shown("With several devices the code starts with “A1-”.")
        assertEquals(1, runBlocking { items.count(archived = false) })
    }


    @Test
    fun `a barcode is linked to an item, finds it and cannot be given to another item`() {
        define(soap)
        launch()
        openItems("1 active item")
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("Code: ART-0001")
        composeRule.typeInForm("Barcode", "6001234567890")
        composeRule.pressInForm("Save")

        composeRule.waitForText("Kept in stock · 6001234567890", substring = true).assertIsDisplayed()
        assertEquals("6001234567890", stored()!!.details.barcode)
        define(soap.copy(code = "ART-0002", designation = "Savon noir"))

        // What a Bluetooth reader types in the search field finds the item.
        composeRule.onNodeWithText("Search by code, designation or barcode").performTextInput("6001234567890")
        composeRule.waitForText("Savon de Marseille").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Savon noir"))

        composeRule.onAllNodesWithText("New item").onFirst().performClick()
        composeRule.waitForText("Create the item")
        composeRule.typeInForm("Designation", "Savon liquide")
        composeRule.typeInForm("Measurement unit", "piece")
        composeRule.typeInForm("Barcode", "6001234567890")
        composeRule.typeInForm("Unit price in BIF", "2500")
        composeRule.pressInForm("Taxes included")
        composeRule.pressInForm("Kept in stock")
        composeRule.pressInForm("Create the item")

        shown("Another item already has this barcode.")
        assertEquals(2, runBlocking { items.count(archived = false) })
    }
    @Test
    fun `the search finds an item by its designation or its code, whatever the accents`() {
        define(soap)
        define(soap.copy(code = "ART-0002", designation = "Café moulu 250 g"))
        define(soap.copy(code = "SRV-0003", designation = "Livraison", stockTracked = false))
        launch()
        openItems("3 active items")
        composeRule.waitForText("Livraison")

        composeRule.onNodeWithText("Search by code, designation or barcode").performTextInput("cafe")
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("Livraison") == 0 }
        composeRule.onNodeWithText("Café moulu 250 g").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Savon de Marseille"))

        composeRule.onNodeWithText("Search by code, designation or barcode").performTextInput("zzz")
        composeRule.waitForText("No item matches this search.").assertIsDisplayed()
    }

    @Test
    fun `an item is changed but its code stays`() {
        define(soap)
        launch()
        openItems("1 active item")
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("Code: ART-0001")
        assertEquals(0, composeRule.onAllNodesWithTextCount("Code"))

        composeRule.replaceInForm("Unit price in BIF", "1750,5")
        composeRule.pressInForm("Save")

        composeRule.waitForText("ART-0001 · 1,750.5 BIF incl. taxes / piece", substring = true).assertIsDisplayed()
        assertEquals(Money.of("1750.5", Currency.BIF), stored()!!.details.price)
        assertEquals("ITEM_UPDATED", runBlocking { auditStore.readAll() }.last().action)
    }

    @Test
    fun `an archived item leaves the list and comes back from the archived items`() {
        define(soap)
        launch()
        openItems("1 active item")
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("Code: ART-0001")

        composeRule.pressInForm("Archive the item")
        shown("This item is archived: it is no longer proposed at the cash register.")
        assertEquals(true, stored()!!.archived)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitForText("Every item is archived.").assertIsDisplayed()
        composeRule.onNodeWithText("Show the archived items").performClick()
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("Code: ART-0001")
        composeRule.pressInForm("Bring the item back")

        composeRule.waitForText("Archive the item")
        assertEquals(false, stored()!!.archived)
    }

    @Test
    fun `an item created before the business was registered for the VAT must receive its VAT`() {
        saveProfile(vatTaxpayer = false)
        define(soap)
        saveProfile(vatTaxpayer = true, version = 2)
        val vat = defineVat()
        launch()

        openItems("1 active item · 1 without VAT")
        composeRule.waitForText("VAT to choose").assertIsDisplayed()
        composeRule.onNodeWithText("Savon de Marseille").performClick()
        shown("The business is registered for the VAT: choose the VAT of this item.")

        composeRule.pressInForm("Save")
        shown("Choose a VAT.")
        composeRule.pressInForm("TVA 18 %")
        composeRule.pressInForm("Save")

        composeRule.waitForText("Search by code, designation or barcode", substring = true)
        composeRule.waitUntil(TIMEOUT_MILLIS) { stored()!!.details.taxes == mapOf(TaxKind.VAT to vat.id) }
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("VAT to choose") == 0 }
    }

    @Test
    fun `a business registered for the VAT cannot create an item before a VAT is defined`() {
        saveProfile(vatTaxpayer = true)
        launch()
        openItems()
        composeRule.waitForText("No item yet")
        composeRule.onAllNodesWithText("New item").onFirst().performClick()
        composeRule.waitForText("Create the item")

        shown("No VAT is defined. Create one in the taxes first.")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
