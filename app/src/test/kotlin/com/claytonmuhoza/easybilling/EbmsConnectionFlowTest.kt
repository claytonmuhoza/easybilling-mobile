package com.claytonmuhoza.easybilling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.testing.TestCertificates
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.openSettings
import com.claytonmuhoza.easybilling.testing.pressInSettings
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.typeInForm
import com.claytonmuhoza.easybilling.testing.waitForText
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

/** Connection to the EBMS (UC-A03 to A05, UC-D08) against the simulated OBR server, over HTTPS. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class EbmsConnectionFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var settings: EbmsSettingsRepository

    @Inject
    lateinit var vault: EbmsCredentialsVault

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var invoices: InvoiceRepository

    @Inject
    lateinit var outbox: OutboxRepository

    @Inject
    lateinit var profiles: TaxProfileRepository

    private val simulator: ObrSimulator = TestCertificates.startSimulator()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun logInAliceThenLaunch() {
        hiltRule.inject()
        runBlocking {
            val alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456"))
            sessions.open(alice.user)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun stop() {
        scenario.close()
        simulator.close()
    }

    private fun openScreen() {
        // The card is below the fold of a small phone since the home screen opens the cash register.
        composeRule.pressInSettings("EBMS connection")
        composeRule.waitForText("Test the connection")
    }

    private fun fillAndSave(password: String = ObrSimulator.DEFAULT_PASSWORD) {
        composeRule.replaceInForm("Server address", simulator.baseUrl)
        composeRule.typeInForm("System identifier", ObrSimulator.DEFAULT_USERNAME)
        composeRule.typeInForm("Password", password)
        composeRule.pressInForm("Save")
        composeRule.waitForText("Settings saved.")
    }

    private fun testConnection(expected: String) {
        composeRule.pressInForm("Test the connection")
        composeRule.waitForText(expected).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the settings ask for the connection while it is not configured`() {
        composeRule.openSettings()
        composeRule.waitForText("EBMS connection").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("To configure").assertIsDisplayed()
    }

    @Test
    fun `a new device starts on the test environment with the address of the specification`() {
        openScreen()

        composeRule.onNodeWithText("Test").assertIsSelected()
        composeRule.onNodeWithText("https://ebms.obr.gov.bi:9443/ebms_api/").assertIsDisplayed()
    }

    @Test
    fun `saved credentials are encrypted apart and a successful test is remembered`() {
        openScreen()
        fillAndSave()

        testConnection("Connection succeeded.")

        assertEquals(
            EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD),
            runBlocking { vault.read(EbmsEnvironment.TEST) },
        )
        assertNotNull(runBlocking { settings.read().lastSuccessfulLogin(EbmsEnvironment.TEST) })
        assertEquals(1, simulator.loginCount)
        assertEquals("EBMS_CONNECTION_TESTED", runBlocking { auditStore.readAll().last().action })
    }

    @Test
    fun `the home screen shows a tested connection`() {
        openScreen()
        fillAndSave()
        testConnection("Connection succeeded.")

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitForText("Test · connected on", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `wrong credentials have their own message`() {
        openScreen()
        fillAndSave(password = "not-the-password")

        testConnection("The OBR refused the user name or the password.")
        assertNull(runBlocking { settings.read().lastSuccessfulLogin(EbmsEnvironment.TEST) })
    }

    @Test
    fun `a server error has its own message`() {
        openScreen()
        fillAndSave()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)

        testConnection("The OBR server had an error. Try again later.")
    }

    @Test
    fun `an unreachable server has its own message`() {
        openScreen()
        fillAndSave()
        simulator.close()

        testConnection("The server cannot be reached. Check the Internet connection.")
    }

    @Test
    fun `a refusal shows the message of the OBR`() {
        openScreen()
        fillAndSave()
        simulator.failNext(400, ObrSimulator.MESSAGE_NO_PASSWORD)

        testConnection("The OBR refused the request: ${ObrSimulator.MESSAGE_NO_PASSWORD}")
    }

    @Test
    fun `an address that is not https is refused`() {
        openScreen()
        composeRule.replaceInForm("Server address", "http://ebms.obr.gov.bi/ebms_api/")

        composeRule.pressInForm("Save")

        composeRule.waitForText("The address must start with https://").performScrollTo().assertIsDisplayed()
        assertNull(runBlocking { vault.read(EbmsEnvironment.TEST) })
    }

    @Test
    fun `the saved password is never shown again and an empty field keeps it`() {
        openScreen()
        fillAndSave()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        openScreen()

        composeRule.onNodeWithText("Leave empty to keep the saved password").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount(ObrSimulator.DEFAULT_PASSWORD, substring = true))
        composeRule.pressInForm("Save")
        composeRule.waitForText("Settings saved.")
        assertEquals(ObrSimulator.DEFAULT_PASSWORD, runBlocking { vault.read(EbmsEnvironment.TEST)?.password })
    }

    @Test
    fun `switching to production asks for the PIN`() {
        openScreen()

        composeRule.onNodeWithText("Production").performClick()
        composeRule.waitForText("Enter your PIN to confirm.")
        composeRule.typeInForm("PIN", "123456")
        composeRule.pressInForm("Confirm")

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Production") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(EbmsEnvironment.PRODUCTION, runBlocking { settings.read().activeEnvironment })
    }

    /** Stores an invoice issued for [environment], as a sale would. */
    private fun issuedInvoice(environment: EbmsEnvironment): Invoice = runBlocking {
        val amount = Money.of("1000", Currency.BIF)
        val line = InvoiceLine(
            itemId = ItemId(1),
            itemCode = "ART-0001",
            designation = "Savon de Marseille",
            unit = "piece",
            stockTracked = true,
            quantity = Quantity.of("1"),
            listUnitPrice = amount,
            priceMode = PriceMode.EXCLUDING_TAXES,
            discount = null,
            vatRate = null,
            amounts = LineAmounts(
                amount, amount, Money.zero(Currency.BIF), Money.zero(Currency.BIF), Money.zero(Currency.BIF),
                amount, Money.zero(Currency.BIF), amount, Money.zero(Currency.BIF), amount,
            ),
        )
        invoices.create(
            InvoiceContent(
                type = DocumentType.FN,
                number = "FN-00001/2026",
                identifier = "4000000001/ws400000000100001/20261003101542/FN-00001/2026",
                issuedAt = Instant.parse("2026-10-03T08:15:42Z"),
                profileVersion = 1,
                environment = environment,
                customer = InvoiceCustomer(CustomerId(1), "Client comptoir", null, "", false),
                paymentType = PaymentType.CASH,
                currency = Currency.BIF,
                lines = listOf(line),
                issuedBy = UserId(1),
            ),
        )
    }

    @Test
    fun `the environment does not change while an invoice waits to be sent`() {
        val invoice = issuedInvoice(EbmsEnvironment.TEST)
        runBlocking {
            // The tax profile the invoice was issued with: without it the invoice could not be sent at all.
            val profile = TaxProfile(
                taxpayerType = TaxpayerType.LEGAL_PERSON,
                name = "Boutique Umuco SPRL",
                tin = "4000000001",
                tradeNumber = "RC 12345",
                postalNumber = "",
                phoneNumber = "+257 22 22 22 22",
                address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
                taxes = TaxRegistrations(),
                fiscalCenter = FiscalCenter.DMC,
                activitySector = "Commerce general",
                legalForm = "SPRL",
            )
            profiles.append(TaxProfileVersion(1, profile, invoice.content.issuedAt, UserId(1)))
            outbox.enqueue(OutboxOperation.SendInvoice(invoice.id), invoice.content.issuedAt)
        }
        openScreen()

        composeRule.onNodeWithText("Production").performClick()
        composeRule.waitForText("Enter your PIN to confirm.")
        composeRule.typeInForm("PIN", "123456")
        composeRule.pressInForm("Confirm")

        composeRule
            .waitForText("Documents are waiting to be sent to the OBR. The environment can change once they are sent.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(EbmsEnvironment.TEST, runBlocking { settings.read().activeEnvironment })
        assertEquals(1, runBlocking { outbox.pending() }.size)
    }

    @Test
    fun `going back to test is refused once a real invoice was issued in production`() {
        issuedInvoice(EbmsEnvironment.PRODUCTION)
        runBlocking { settings.write(settings.read().copy(activeEnvironment = EbmsEnvironment.PRODUCTION)) }
        openScreen()

        composeRule.onNodeWithText("Test").performClick()

        composeRule
            .waitForText("Real invoices were issued in production: going back to test is no longer possible.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(EbmsEnvironment.PRODUCTION, runBlocking { settings.read().activeEnvironment })
    }

    @Test
    fun `a wrong PIN keeps the test environment`() {
        openScreen()

        composeRule.onNodeWithText("Production").performClick()
        composeRule.waitForText("Enter your PIN to confirm.")
        composeRule.typeInForm("PIN", "000000")
        composeRule.pressInForm("Confirm")

        composeRule.waitForText("Wrong PIN. 9 attempts left.").performScrollTo().assertIsDisplayed()
        assertEquals(EbmsEnvironment.TEST, runBlocking { settings.read().activeEnvironment })
    }

    @Test
    fun `the exchange is in the log with its password masked`() {
        openScreen()
        fillAndSave()
        testConnection("Connection succeeded.")

        composeRule.onNodeWithText("Exchanges").performClick()
        composeRule.waitForText("login/ · 200").performClick()

        composeRule.waitForText("Request").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("\"password\":\"••••\"", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount(ObrSimulator.DEFAULT_PASSWORD, substring = true))
        assertEquals(0, composeRule.onAllNodesWithTextCount("simulated-token-1", substring = true))
    }

    @Test
    fun `the retention of the log can be changed`() {
        openScreen()
        composeRule.onNodeWithText("Exchanges").performClick()
        composeRule.waitForText("Kept 90 days")

        composeRule.onNodeWithText("Change").performClick()
        composeRule.onNodeWithText("Days").performTextClearance()
        composeRule.onNodeWithText("Days").performTextInput("30")
        composeRule.onNodeWithText("Confirm").performClick()

        composeRule.waitForText("Kept 30 days").assertIsDisplayed()
        assertEquals(30, runBlocking { settings.read().exchangeRetentionDays })
    }

    @Test
    fun `the log is empty before the first exchange`() {
        openScreen()

        composeRule.onNodeWithText("Exchanges").performClick()

        composeRule.waitForText("No exchange yet").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the screen is translated in French`() {
        composeRule.pressInSettings("Connexion EBMS", settings = "Paramètres")

        composeRule.waitForText("Tester la connexion").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Adresse du serveur").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Échanges").assertIsDisplayed()
    }
}
