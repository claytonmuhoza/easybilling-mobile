package com.claytonmuhoza.easybilling

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import com.claytonmuhoza.easybilling.domain.document.DocumentSettingsRepository
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
import com.claytonmuhoza.easybilling.testing.RecordingPdfWriter
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.pressInForm
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The PDF of an invoice, opened or shared, and the header of the invoices (UC-G02, G03, G04,
 * A08), by the administrator "Alice", already logged in. The PDF is written by a recording
 * writer: the device support for PDF files does not exist on the JVM.
 *
 * The names of the tests are short: Robolectric puts the cache in a folder named after the test,
 * and on Windows a long name exceeds the longest path.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class DocumentsFlowTest {

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
    lateinit var documentSettings: DocumentSettingsRepository

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var writer: RecordingPdfWriter

    @Inject
    lateinit var clock: TestClock

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var alice: User
    private lateinit var soap: Item
    private var scenario: ActivityScenario<MainActivity>? = null

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

    /** A business with one registered invoice of one soap, FN-00001/2026; nothing waits to be sent. */
    private fun registeredInvoice(): Invoice = runBlocking {
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
        val draft = editSale.addItem(soap.id)
        val invoice = assertIs<IssueSale.Result.Issued>(issueSale(draft.id, alice.id)).invoice
        outbox.pending().forEach { outbox.remove(it.id) }
        invoices.setStatus(invoice.id, InvoiceStatus.Registered, clock.now())
        invoice
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Welcome, Alice")
    }

    /** Opens the detail of the only invoice, from the latest invoices of the home screen. */
    private fun openInvoice() {
        launch()
        composeRule.waitForText("FN-00001/2026").performScrollTo().performClick()
        composeRule.waitForText("At the OBR")
        shadowOf(application).clearNextStartedActivities()
    }

    /** Waits for the application to start another one, and gives the request it made. */
    private fun startedIntent(): Intent {
        var started: Intent? = null
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            // Lets the main thread work: the PDF is created off it, then handed over on it.
            composeRule.waitForIdle()
            started = started ?: shadowOf(application).nextStartedActivity
            started != null
        }
        return assertNotNull(started)
    }

    private fun audited(): List<String> = runBlocking { auditStore.readAll() }.map { it.action }

    @Test
    fun `the PDF is shared and traced`() {
        val invoice = registeredInvoice()
        openInvoice()

        composeRule.pressInForm("Share")

        val chooser = startedIntent()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        val uri = assertNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content://com.claytonmuhoza.easybilling.files/invoices/Facture-FN-00001-2026.pdf", uri.toString())
        // Only the receiver may read the file, and only that file.
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The file is in the folder of the cache the application shares.
        val shared = File(application.cacheDir, "invoices/Facture-FN-00001-2026.pdf").readBytes()
        assertEquals("%PDF simulated FN-00001/2026", shared.decodeToString())

        val document = writer.documents.single()
        assertEquals(invoice.content.identifier, document.identifier.value)
        assertNull(document.qrContent)
        assertEquals("INVOICE_PDF_CREATED", audited().last())
    }

    @Test
    fun `the PDF opens in a PDF application`() {
        registeredInvoice()
        openInvoice()

        composeRule.pressInForm("Open the PDF")

        val view = startedIntent()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/pdf", view.type)
        assertEquals("Facture-FN-00001-2026.pdf", view.data?.lastPathSegment)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `without a PDF application it says so`() {
        registeredInvoice()
        openInvoice()
        // From now on, starting an application the device does not have fails as on a real device.
        shadowOf(application).checkActivities(true)

        composeRule.pressInForm("Open the PDF")

        composeRule.waitForText("No application on this device can open a PDF. You can still share it.")
            .performScrollTo()
            .assertIsDisplayed()
        // The PDF was created all the same: it is traced.
        assertEquals("INVOICE_PDF_CREATED", audited().last())
    }

    @Test
    fun `the footer and QR code go on the PDF`() {
        val invoice = registeredInvoice()
        launch()
        composeRule.openSettings()
        composeRule.pressInForm("Invoice header")
        composeRule.waitForText("No logo").assertIsDisplayed()
        composeRule.onNodeWithText("Print a QR code of the identifier").performScrollTo().assertIsOff()

        composeRule.typeInForm("Footer", "Merci de votre visite")
        composeRule.pressInForm("Print a QR code of the identifier")
        composeRule.onNodeWithText("Print a QR code of the identifier").assertIsOn()
        composeRule.pressInForm("Save")

        composeRule.waitForText("Saved. The next PDF uses these settings.").performScrollTo().assertIsDisplayed()
        assertEquals(
            DocumentSettings(footer = "Merci de votre visite", showQrCode = true),
            runBlocking { documentSettings.read() },
        )

        scenario?.close()
        openInvoice()
        composeRule.pressInForm("Share")
        startedIntent()

        val document = writer.documents.single()
        assertEquals("Merci de votre visite", document.footer)
        // The QR code carries the identifier of the invoice.
        assertEquals(invoice.content.identifier, document.qrContent)
    }

    @Test
    fun `a footer too long is refused`() {
        launch()
        composeRule.openSettings()
        composeRule.pressInForm("Invoice header")
        composeRule.waitForText("No logo")

        composeRule.typeInForm("Footer", "x".repeat(DocumentSettings.MAX_FOOTER_LENGTH + 1))
        composeRule.pressInForm("Save")

        composeRule.waitForText("The footer is too long.").performScrollTo().assertIsDisplayed()
        assertEquals(DocumentSettings(), runBlocking { documentSettings.read() })
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
