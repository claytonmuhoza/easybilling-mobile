package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.claytonmuhoza.easybilling.domain.configuration.TaxForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRateForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.PrinterSettings
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.Receipt
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.testing.RecordingReceiptPrinter
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressNewSale
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
import kotlin.test.assertTrue

/**
 * The printer of the tickets and the printing of an invoice (UC-A09, UC-G01), by the
 * administrator "Alice", already logged in. The printer is a recording one: the JVM has no
 * Bluetooth.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class PrintingFlowTest {

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
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var outbox: OutboxRepository

    @Inject
    lateinit var editSale: EditSale

    @Inject
    lateinit var issueSale: IssueSale

    @Inject
    lateinit var printerSettings: PrinterSettingsRepository

    @Inject
    lateinit var printer: RecordingReceiptPrinter

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private lateinit var alice: User
    private lateinit var soap: Item
    private var scenario: ActivityScenario<MainActivity>? = null
    private val pos58 = PairedPrinter("00:11:22:33:44:55", "POS-58")

    @Before
    fun logInAlice() {
        hiltRule.inject()
        runBlocking {
            val created = createAdministrator("Alice", "123456", "123456")
            alice = assertIs<CreateAdministrator.Result.Created>(created).user
            sessions.open(alice)
        }
    }

    @After
    fun stop() {
        scenario?.close()
    }

    /** A business ready to sell soap at 1 000 without taxes; the OBR is out of reach. */
    private fun readyToSell() = runBlocking {
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
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("ws400000000100001", "password"))
        ebmsSettings.write(
            ebmsSettings.read().copy(
                serverUrls = mapOf(EbmsEnvironment.TEST to "https://127.0.0.1:9/ebms_api/"),
                lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to clock.now()),
            ),
        )
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

    /** One registered invoice of one soap, FN-00001/2026; nothing waits to be sent. */
    private fun registeredInvoice(): Invoice = runBlocking {
        readyToSell()
        val draft = editSale.addItem(soap.id)
        val invoice = assertIs<IssueSale.Result.Issued>(issueSale(draft.id, alice.id)).invoice
        outbox.pending().forEach { outbox.remove(it.id) }
        invoices.setStatus(invoice.id, InvoiceStatus.Registered, clock.now())
        invoice
    }

    private fun associate(settings: PrinterSettings = PrinterSettings(printer = pos58)) =
        runBlocking { printerSettings.write(settings) }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Welcome, Alice")
    }

    /** Opens the detail of the only invoice, from the latest invoices of the home screen. */
    private fun openInvoice() {
        launch()
        composeRule.waitForText("FN-00001/2026").performScrollTo().performClick()
        composeRule.waitForText("At the OBR")
    }

    private fun openPrinterSettings() {
        launch()
        composeRule.openSettings()
        composeRule.pressInForm("Printer")
        composeRule.waitForText("Paired printers")
    }

    private fun shown(text: String) = composeRule.waitForText(text).performScrollTo().assertIsDisplayed()

    private fun stored() = runBlocking { printerSettings.read() }

    private fun audited(): List<String> = runBlocking { auditStore.readAll() }.map { it.action }

    private fun Receipt.text() = lines.joinToString("\n") { it.text }

    @Test
    fun `a paired printer is associated, set and tested`() {
        openPrinterSettings()
        // Nothing is associated yet: no test page can be asked for.
        composeRule.onNodeWithText("Print a test page").performScrollTo().assertIsNotEnabled()

        composeRule.pressInForm("POS-58")
        composeRule.waitUntil(TIMEOUT_MILLIS) { stored().printer == pos58 }
        composeRule.pressInForm("80 mm")
        composeRule.pressInForm("Print accents")
        composeRule.waitUntil(TIMEOUT_MILLIS) { stored().accents }
        assertEquals(PrinterSettings(pos58, ReceiptWidth.MM_80, accents = true), stored())

        composeRule.pressInForm("Print a test page")

        shown("Ticket sent to the printer.")
        val (address, page) = printer.printed.single()
        assertEquals("00:11:22:33:44:55", address)
        assertEquals(ReceiptWidth.MM_80, page.width)
        assertTrue("PAGE DE TEST" in page.text() && "POS-58" in page.text(), page.text())
    }

    @Test
    fun `the settings show the associated printer, which can be dissociated`() {
        associate()
        launch()
        composeRule.openSettings()
        shown("POS-58")
        composeRule.pressInForm("Printer")
        composeRule.waitForText("Paired printers")
        // The associated printer is the selected choice, whether its name is on the choice or inside it.
        composeRule.onNode(isSelected() and (hasText("POS-58") or hasAnyDescendant(hasText("POS-58")))).assertExists()

        composeRule.pressInForm("No printer")

        composeRule.waitUntil(TIMEOUT_MILLIS) { stored().printer == null }
        composeRule.onNodeWithText("Print a test page").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `without the permission or with Bluetooth off the screen says what to do`() {
        printer.lookup = PrinterLookup.PermissionMissing
        launch()
        composeRule.openSettings()
        composeRule.pressInForm("Printer")

        shown("Allow Bluetooth")
        shown(
            "The application needs your permission to connect to Bluetooth devices. " +
                "It never searches for devices and does not use your location.",
        )

        printer.lookup = PrinterLookup.BluetoothOff
        scenario?.recreate()
        shown("Bluetooth is off. Turn it on to choose a printer.")

        printer.lookup = PrinterLookup.Found(emptyList())
        composeRule.pressInForm("Check again")
        shown("No device is paired. Pair the printer in the Bluetooth settings of Android, then come back.")
    }

    @Test
    fun `the ticket of an invoice is printed on the associated printer, and traced`() {
        val invoice = registeredInvoice()
        associate()
        openInvoice()

        composeRule.pressInForm("Print the ticket")

        shown("Ticket sent to the printer.")
        val (address, receipt) = printer.printed.single()
        assertEquals("00:11:22:33:44:55", address)
        assertEquals(ReceiptWidth.MM_58, receipt.width)
        assertEquals("FACTURE", receipt.lines.first().text)
        assertTrue(invoice.content.identifier in receipt.text().filterNot { it.isWhitespace() })
        assertTrue("Boutique Umuco SPRL" in receipt.text() && "Total a payer" in receipt.text(), receipt.text())
        assertEquals("INVOICE_PRINTED", audited().last())
    }

    @Test
    fun `without a printer, or with one that does not answer, nothing is printed nor traced`() {
        registeredInvoice()
        openInvoice()

        composeRule.pressInForm("Print the ticket")
        shown("No printer is associated. Choose one in Settings › Printer.")

        associate()
        printer.outcome = PrintOutcome.UNREACHABLE
        composeRule.pressInForm("Print the ticket")
        shown("The printer does not answer. Check that it is on, close by and has paper.")

        printer.outcome = PrintOutcome.BLUETOOTH_OFF
        composeRule.pressInForm("Print the ticket")
        shown("Bluetooth is off. Turn it on, then print again.")

        assertEquals(emptyList(), printer.printed)
        assertEquals(0, audited().count { it == "INVOICE_PRINTED" })
    }

    @Test
    fun `with the option, the ticket is printed by itself once a sale is validated`() {
        readyToSell()
        associate(PrinterSettings(printer = pos58, autoPrint = true))
        launch()

        composeRule.pressNewSale()
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("1 item · 1,180 BIF")
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.waitForText("Validate the sale")
        composeRule.pressInForm("Validate the sale")
        composeRule.waitForText("Invoice FN-00001/2026")

        shown("Ticket sent to the printer.")
        assertEquals(1, printer.printed.size)
        assertTrue("FN-00001/2026" in printer.printed.single().second.text())
        // Printed while the OBR has not answered: the ticket says so.
        assertTrue("En cours de transmission" in printer.printed.single().second.text())

        // The same screen again prints nothing more.
        scenario?.recreate()
        composeRule.waitForText("Invoice FN-00001/2026")
        composeRule.waitForIdle()
        assertEquals(1, printer.printed.size)
    }

    @Test
    fun `without the option a sale prints nothing by itself`() {
        readyToSell()
        associate()
        launch()

        composeRule.pressNewSale()
        composeRule.waitForText("Savon de Marseille").performClick()
        composeRule.waitForText("1 item · 1,180 BIF")
        composeRule.onNodeWithText("Checkout").performClick()
        composeRule.waitForText("Validate the sale")
        composeRule.pressInForm("Validate the sale")
        composeRule.waitForText("Invoice FN-00001/2026")
        shown("Print the ticket")

        assertEquals(emptyList(), printer.printed)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
