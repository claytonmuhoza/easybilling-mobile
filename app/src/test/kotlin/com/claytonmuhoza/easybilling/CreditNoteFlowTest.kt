package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineForm
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
import com.claytonmuhoza.easybilling.domain.invoicing.CreditRequest
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueCreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.transmission.DispatchOutbox
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.enterPin
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressKey
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.typeInForm
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The credit note (UC-E03), from the detail of an invoice to the OBR, by the administrator "Alice",
 * already logged in, on a phone. The OBR is the simulated server, over HTTPS.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class CreditNoteFlowTest {

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
    lateinit var issueCreditNote: IssueCreditNote

    @Inject
    lateinit var dispatchOutbox: DispatchOutbox

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var createUser: CreateUser

    @Inject
    lateinit var changeTemporaryPin: ChangeTemporaryPin

    @Inject
    lateinit var auditStore: AuditStore

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var alice: User
    private lateinit var soap: Item
    private var scenario: ActivityScenario<MainActivity>? = null

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

    /** A business ready to sell soap at 1 000 without taxes, whose EBMS is the simulated server. */
    @Before
    fun readyToSell() {
        hiltRule.inject()
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
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
            val tax = TaxForm(TaxKind.VAT, "TVA 18 %", rate)
            val vat = assertIs<CreateTax.Result.Created>(createTax(tax, alice.id)).tax
            val form = ItemForm(
                code = "ART-0001",
                designation = "Savon de Marseille",
                unit = "piece",
                price = "1000",
                priceMode = PriceMode.EXCLUDING_TAXES,
                taxes = mapOf(TaxKind.VAT to vat.id),
                // Sold without stock: the stock and the sale have their own tests (StockSalesFlowTest).
                stockTracked = false,
            )
            soap = assertIs<CreateItem.Result.Created>(createItem(form, alice.id)).item
        }
    }

    @After
    fun stop() {
        scenario?.close()
        simulator.close()
    }

    /**
     * Issues an invoice of [quantity] soaps without the screens.
     *
     * @param atObr true to send it to the simulated OBR, which registers it; false to mark it
     * registered on the device only: the OBR then knows nothing of it.
     */
    private fun sold(quantity: Int, atObr: Boolean): Invoice = runBlocking {
        repeat(quantity) { editSale.addItem(soap.id) }
        val invoice = assertIs<IssueSale.Result.Issued>(issueSale(editSale.current().id, alice.id)).invoice
        if (atObr) {
            dispatchOutbox()
        } else {
            outbox.pending().forEach { outbox.remove(it.id) }
            invoices.setStatus(invoice.id, InvoiceStatus.Registered, clock.now())
        }
        assertEquals(InvoiceStatus.Registered, invoices.statusOf(invoice.id))
        clock.advance(Duration.ofSeconds(1))
        invoice
    }

    /** Opens the detail of [number] from the list of the invoices. */
    private fun open(number: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Invoices").performClick()
        composeRule.waitForText("Number or customer")
        composeRule.waitForText(number).performClick()
        composeRule.waitForText("At the OBR")
    }

    private fun shown(text: String, substring: Boolean = false) =
        composeRule.waitForText(text, substring).performScrollTo().assertIsDisplayed()

    private fun creditNotesOf(invoice: Invoice) = runBlocking { invoices.creditNotesOf(invoice.content.number) }

    private fun statusOf(invoice: Invoice) = runBlocking { invoices.statusOf(invoice.id) }

    private fun sentDocument(number: String): JsonObject = simulator.requests
        .filter { it.url.encodedPath.endsWith("addInvoice_confirm/") }
        .map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject }
        .single { it.text("invoice_number") == number }

    private fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content

    @Test
    fun `a part of an invoice is credited with its reason, and the OBR registers the credit note`() {
        val invoice = sold(quantity = 3, atObr = true)
        open("FN-00001/2026")

        composeRule.pressInForm("Issue a credit note")
        composeRule.waitForText("Lines to credit")
        shown("Invoice FN-00001/2026. Left to credit: 3,540 BIF.")
        composeRule.pressInForm("Issue the credit note")
        shown("Tick at least one line to credit.")

        composeRule.pressInForm("Savon de Marseille")
        composeRule.waitForText("Quantity to credit")
        composeRule.replaceInForm("Quantity to credit", "4")
        shown("More than what is left to credit on this line")
        composeRule.replaceInForm("Quantity to credit", "1")
        composeRule.pressInForm("Issue the credit note")
        shown("Choose the reason of the credit note")
        composeRule.pressInForm("Goods returned")
        composeRule.typeInForm("Detail of the reason", "carton abîmé")
        composeRule.pressInForm("Issue the credit note")

        // The credit note takes the place of its form.
        composeRule.waitForText("Credit note on the invoice FN-00001/2026")
        shown("Reason sent to the OBR: Retour marchandises : carton abîmé")
        val note = creditNotesOf(invoice).single()
        assertEquals("FA-00001/2026", note.content.number)
        composeRule.waitUntil(TIMEOUT_MILLIS) { statusOf(note) == InvoiceStatus.Registered }
        val sent = sentDocument("FA-00001/2026")
        assertEquals("FA", sent.text("invoice_type"))
        assertEquals("FN-00001/2026", sent.text("invoice_ref"))
        assertEquals("Retour marchandises : carton abîmé", sent.text("cn_motif"))
        val line = (sent.getValue("invoice_items") as JsonArray).single().jsonObject
        assertEquals("1", line.text("item_quantity"))
        assertEquals("1180", line.text("item_total_amount"))

        // Back on the invoice: it lists its credit note and can no longer be cancelled.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForText("Credit notes")
        shown("FA-00001/2026")
        composeRule.onNodeWithText("Cancel the invoice").assertDoesNotExist()
        shown("Issue a credit note")
    }

    @Test
    fun `a cashier issues a credit note with the PIN of a manager`() {
        val invoice = sold(quantity = 1, atObr = false)
        val marc = account("Marc", Role.MANAGER, pin = "333333")
        val bob = account("Bob", Role.CASHIER, pin = "222222")
        runBlocking { sessions.open(bob) }
        open("FN-00001/2026")

        composeRule.pressInForm("Issue a credit note")
        composeRule.waitForText("Lines to credit")
        composeRule.pressInForm("Credit all")
        composeRule.waitForText("Quantity to credit")
        composeRule.pressInForm("Error")
        composeRule.pressInForm("Issue the credit note")
        composeRule.waitForText("Approval of a manager")
        assertEquals(emptyList(), creditNotesOf(invoice))
        composeRule.waitForText("Marc").performClick()
        composeRule.enterPin("333333")
        composeRule.pressKey("Approve")

        composeRule.waitForText("Credit note on the invoice FN-00001/2026")
        val note = creditNotesOf(invoice).single()
        assertEquals(bob.id, note.content.issuedBy)
        assertEquals(invoice.content.totals.total, note.content.totals.total)
        assertEquals(marc.id.value.toString(), approverOf(note))
    }

    /** @return the approver the audit log names for the issue of [note]. */
    private fun approverOf(note: Invoice): String? = runBlocking {
        auditStore.readAll().single { it.action == "CREDIT_NOTE_ISSUED" && "invoice=${note.id.value};" in it.details }
            .details.substringAfter("approver=", missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }

    @Test
    fun `an invoice credited in full is no longer credited, and the list is filtered by type`() {
        val invoice = sold(quantity = 2, atObr = true)
        runBlocking {
            val request = CreditRequest(
                invoice.id,
                listOf(CreditLineForm(soap.id, "2", "1000")),
                CreditReasonType.ERROR,
                detail = "",
            )
            assertIs<IssueCreditNote.Result.Issued>(issueCreditNote(request, alice.id))
            dispatchOutbox()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForText("Invoices").performClick()
        composeRule.waitForText("Number or customer")

        // The filter by type is offered now that a credit note exists.
        composeRule.waitForText("All types").assertExists()
        listed("FA-00001/2026", "FN-00001/2026")
        composeRule.onNode(hasText("Credit notes (FA)") and hasClickAction()).performClick()
        listed("FA-00001/2026")
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("FN-00001/2026") == 0 }
        composeRule.onNode(hasText("Invoices (FN)") and hasClickAction()).performClick()
        listed("FN-00001/2026")
        composeRule.waitUntil(TIMEOUT_MILLIS) { composeRule.onAllNodesWithTextCount("FA-00001/2026") == 0 }

        composeRule.waitForText("FN-00001/2026").performClick()
        composeRule.waitForText("At the OBR")
        shown("Credit notes")
        composeRule.onNodeWithText("Issue a credit note").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel the invoice").assertDoesNotExist()
    }

    /** The list holds [texts]; it is scrolled to each, since it only draws the rows on screen. */
    private fun listed(vararg texts: String) = texts.forEach { text ->
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runCatching { composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)) }.isSuccess
        }
    }

    /** Creates [name] with [roles], who then chooses [pin], as Alice. */
    private fun account(name: String, vararg roles: Role, pin: String): User = runBlocking {
        val user = assertIs<CreateUser.Result.Created>(
            createUser(UserForm(name, roles.toSet()), "111111", "111111", alice.id),
        ).user
        changeTemporaryPin(user.id, "111111", pin, pin)
        // Choosing the PIN opened a session: the session of the test comes back to Alice.
        sessions.open(alice)
        user
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
