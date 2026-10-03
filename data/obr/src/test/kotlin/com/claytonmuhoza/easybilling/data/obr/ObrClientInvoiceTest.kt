package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.LookupOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.domain.transmission.ObrMessages
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import java.math.BigDecimal
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `addInvoice_confirm/` and `getInvoice/` against the simulated OBR server (UC-C01, C07, D09). */
class ObrClientInvoiceTest {

    private val simulator = ObrSimulator()
    private val exchanges = mutableListOf<ObrExchange>()
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val client = ObrClient(
        ObrHttp.client(timeout = Duration.ofSeconds(1)),
        ExchangeRecorder { exchanges.add(it) },
        TimeProvider { now },
    )
    private lateinit var target: LoginTarget

    private val seller = TaxProfile(
        taxpayerType = TaxpayerType.LEGAL_PERSON,
        name = "Boutique Umuco SPRL",
        tin = "4000000001",
        tradeNumber = "RC 12345",
        postalNumber = "",
        phoneNumber = "+257 22 22 22 22",
        address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
        taxes = TaxRegistrations(vat = true, consumptionTax = true, flatRateWithholding = true),
        fiscalCenter = FiscalCenter.DMC,
        activitySector = "Commerce general",
        legalForm = "SPRL",
    )

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    /** The second line of the example of the specification: 10 × 200, TC 30, PFL 25. */
    private val line = InvoiceLine(
        itemId = ItemId(1),
        itemCode = "ART-0001",
        designation = "Article 2",
        unit = "pièce",
        stockTracked = true,
        quantity = Quantity.of("10"),
        listUnitPrice = bif("200"),
        priceMode = PriceMode.EXCLUDING_TAXES,
        discount = null,
        vatRate = BigDecimal("18"),
        amounts = LineAmounts(
            unitPrice = bif("200"),
            price = bif("2000"),
            consumptionTax = bif("30"),
            ott = bif("0"),
            tsce = bif("0"),
            priceWithoutVat = bif("2030"),
            vat = bif("365.4"),
            priceWithVat = bif("2395.4"),
            flatRateWithholding = bif("25"),
            total = bif("2420.4"),
        ),
    )

    private fun invoice(
        number: String = "FN-00001/2026",
        customer: InvoiceCustomer = InvoiceCustomer(CustomerId(1), "Client comptoir", null, "", false),
    ) = Invoice(
        id = InvoiceId(1),
        content = InvoiceContent(
            type = DocumentType.FN,
            number = number,
            identifier = "4000000001/ws400000000100001/20261003101542/$number",
            issuedAt = now,
            profileVersion = 1,
            environment = EbmsEnvironment.TEST,
            customer = customer,
            paymentType = PaymentType.CREDIT,
            currency = Currency.BIF,
            lines = listOf(line, line.copy(designation = "Savon de Marseille", quantity = Quantity.of("2.5"))),
            issuedBy = UserId(1),
        ),
    )

    @BeforeTest
    fun start() {
        simulator.start()
        val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        target = LoginTarget(EbmsEnvironment.TEST, simulator.baseUrl, credentials)
    }

    @AfterTest
    fun stop() = simulator.close()

    private suspend fun token(): String = assertIs<LoginOutcome.Success>(client.login(target)).token

    private suspend fun send(invoice: Invoice = invoice(), seller: TaxProfile = this.seller) =
        client.addInvoice(target, token(), invoice, seller)

    @Test
    fun `an accepted invoice gives the acknowledgement of the OBR, kept as received`() = runTest {
        val receipt = assertIs<SendOutcome.Accepted>(send()).receipt

        assertEquals("4530253", receipt.registeredNumber)
        assertEquals("2026-10-03 10:04:01", receipt.registeredDate)
        assertEquals("signature-1", receipt.signature)
        val raw = Json.parseToJsonElement(receipt.rawResponse).jsonObject
        assertEquals(setOf("success", "msg", "result", "electronic_signature"), raw.keys)
        assertEquals(ObrSimulator.MESSAGE_INVOICE_ADDED, (raw["msg"] as JsonPrimitive).content)
    }

    @Test
    fun `the signature is read under both spellings of the specification`() = runTest {
        simulator.alternateSignatureName = true

        assertEquals("signature-1", assertIs<SendOutcome.Accepted>(send()).receipt.signature)
    }

    @Test
    fun `the request is a JSON POST with the token and never holds null`() = runTest {
        val token = token()

        client.addInvoice(target, token, invoice(), seller)

        val request = simulator.requests.last()
        assertEquals("POST", request.method)
        assertEquals("/ebms_api/addInvoice_confirm/", request.url.encodedPath)
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
        assertFalse("null" in request.body!!.utf8())
    }

    /** @return the texts of [json]; a value that is not a text fails the test. */
    private fun texts(json: Map<String, JsonElement>): Map<String, String> =
        json.mapValues { (it.value as JsonPrimitive).also { value -> assertTrue(value.isString) }.content }

    private suspend fun sentBody(): JsonObject {
        send()
        return Json.parseToJsonElement(simulator.requests.last().body!!.utf8()).jsonObject
    }

    @Test
    fun `the header holds every field of the specification, as text`() = runTest {
        assertEquals(
            mapOf(
                "invoice_number" to "FN-00001/2026",
                // 08:15:42 in UTC is 10:15:42 in Bujumbura.
                "invoice_date" to "2026-10-03 10:15:42",
                "invoice_type" to "FN",
                "tp_type" to "2",
                "tp_name" to "Boutique Umuco SPRL",
                "tp_TIN" to "4000000001",
                "tp_trade_number" to "RC 12345",
                "tp_postal_number" to "",
                "tp_phone_number" to "+257 22 22 22 22",
                "tp_address_province" to "Bujumbura Mairie",
                "tp_address_commune" to "Mukaza",
                "tp_address_quartier" to "Rohero",
                "tp_address_avenue" to "Avenue de l'Industrie",
                "tp_address_rue" to "",
                "tp_address_number" to "",
                "vat_taxpayer" to "1",
                "ct_taxpayer" to "1",
                "tl_taxpayer" to "1",
                "tp_fiscal_center" to "DMC",
                "tp_activity_sector" to "Commerce general",
                "tp_legal_form" to "SPRL",
                "payment_type" to "3",
                "invoice_currency" to "BIF",
                "customer_name" to "Client comptoir",
                "customer_TIN" to "",
                "customer_address" to "",
                "vat_customer_payer" to "0",
                "cancelled_invoice_ref" to "",
                "invoice_ref" to "",
                "cn_motif" to "",
                "invoice_identifier" to "4000000001/ws400000000100001/20261003101542/FN-00001/2026",
            ),
            texts(sentBody().filterKeys { it != "invoice_items" }),
        )
    }

    @Test
    fun `each line holds the amounts of the formula of the OBR, as text`() = runTest {
        val items = sentBody().getValue("invoice_items").jsonArray.map { it.jsonObject }

        assertEquals(
            mapOf(
                "item_designation" to "Article 2",
                "item_quantity" to "10",
                "item_price" to "200",
                "item_ct" to "30",
                "item_tl" to "25",
                "item_ott_tax" to "0",
                "item_tsce_tax" to "0",
                "item_price_nvat" to "2030",
                "vat" to "365.4",
                "item_price_wvat" to "2395.4",
                "item_total_amount" to "2420.4",
            ),
            texts(items[0]),
        )
        assertEquals("2.5", (items[1]["item_quantity"] as JsonPrimitive).content)
    }

    @Test
    fun `a customer registered for the VAT is sent with its TIN`() = runTest {
        val customer = InvoiceCustomer(CustomerId(2), "Brasserie du Lac SA", ObrSimulator.KNOWN_TIN, "Rohero", true)

        assertIs<SendOutcome.Accepted>(send(invoice(customer = customer)))

        val sent = simulator.invoices.values.single()
        assertEquals(ObrSimulator.KNOWN_TIN, (sent["customer_TIN"] as JsonPrimitive).content)
        assertEquals("1", (sent["vat_customer_payer"] as JsonPrimitive).content)
        assertEquals("Rohero", (sent["customer_address"] as JsonPrimitive).content)
    }

    @Test
    fun `a refusal keeps the message of the OBR`() = runTest {
        val unknown = InvoiceCustomer(CustomerId(2), "Inconnu SA", "999", "", true)
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN),
            send(invoice(customer = unknown)),
        )
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_MISSING_FIELDS),
            send(seller = seller.copy(tradeNumber = "")),
        )
        assertEquals(emptyMap(), simulator.invoices)
    }

    @Test
    fun `the two refusals the send queue reacts to are the messages of the specification`() = runTest {
        assertIs<SendOutcome.Accepted>(send())

        assertEquals(SendOutcome.Rejected(ObrMessages.DUPLICATE_NUMBER), send())
        assertEquals(ObrSimulator.MESSAGE_DUPLICATE_NUMBER, ObrMessages.DUPLICATE_NUMBER)
        assertEquals(ObrSimulator.MESSAGE_DATE_IN_FUTURE, ObrMessages.DATE_IN_FUTURE)
    }

    @Test
    fun `a refused token asks for a new one`() = runTest {
        val token = token()
        simulator.expireTokens()

        assertEquals(SendOutcome.TokenRefused, client.addInvoice(target, token, invoice(), seller))
        assertEquals(LookupOutcome.TokenRefused, client.getInvoice(target, token, invoice().content.identifier))
    }

    @Test
    fun `a server error or a server that is down is not delivered, and can be sent again`() = runTest {
        val token = token()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(SendOutcome.NotDelivered("HTTP 500"), client.addInvoice(target, token, invoice(), seller))

        val freePort = ServerSocket(0).use { it.localPort }
        val nobody = target.copy(serverUrl = "http://127.0.0.1:$freePort/ebms_api/")
        assertIs<SendOutcome.NotDelivered>(client.addInvoice(nobody, token, invoice(), seller))
        assertEquals(emptyMap(), simulator.invoices)
    }

    @Test
    fun `a lost acknowledgement is uncertain, and the OBR then says it has the invoice`() = runTest {
        val token = token()
        simulator.loseNextAcknowledgement()

        assertIs<SendOutcome.Uncertain>(client.addInvoice(target, token, invoice(), seller))

        assertEquals(LookupOutcome.Found, client.getInvoice(target, token, invoice().content.identifier))
    }

    @Test
    fun `a request without an answer in time is uncertain`() = runTest {
        val token = token()
        simulator.delayNext(seconds = 3)

        assertIs<SendOutcome.Uncertain>(client.addInvoice(target, token, invoice(), seller))
    }

    @Test
    fun `a success that cannot be read is uncertain, not accepted nor rejected`() = runTest {
        val token = token()
        for (body in listOf("""{"success":true,"msg":"ok"}""", "<html>Maintenance</html>")) {
            simulator.forceNext(MockResponse.Builder().code(200).body(body).build())
            assertIs<SendOutcome.Uncertain>(client.addInvoice(target, token, invoice(), seller), body)
        }
    }

    @Test
    fun `an invoice is found by its identifier, or unknown`() = runTest {
        val token = token()
        client.addInvoice(target, token, invoice(), seller)

        assertEquals(LookupOutcome.Found, client.getInvoice(target, token, invoice().content.identifier))
        assertEquals(LookupOutcome.NotFound, client.getInvoice(target, token, "4000000001/ws/20261003101542/X"))

        val request = simulator.requests.last()
        assertEquals("/ebms_api/getInvoice/", request.url.encodedPath)
        assertEquals("""{"invoice_identifier":"4000000001/ws/20261003101542/X"}""", request.body!!.utf8())
    }

    @Test
    fun `while the OBR cannot answer, nothing is concluded about an invoice`() = runTest {
        val token = token()
        val identifier = invoice().content.identifier

        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(LookupOutcome.Unavailable("HTTP 500"), client.getInvoice(target, token, identifier))

        simulator.failNext(400, ObrSimulator.MESSAGE_NO_IDENTIFIER)
        assertEquals(LookupOutcome.Unavailable("HTTP 400"), client.getInvoice(target, token, identifier))

        val empty = """{"success":true,"result":{"invoices":[]}}"""
        simulator.forceNext(MockResponse.Builder().code(200).body(empty).build())
        assertEquals(LookupOutcome.Unavailable("HTTP 200"), client.getInvoice(target, token, identifier))

        simulator.dropNext()
        assertIs<LookupOutcome.Unavailable>(client.getInvoice(target, token, identifier))
    }

    @Test
    fun `the exchanges are recorded without the token`() = runTest {
        val token = token()
        client.addInvoice(target, token, invoice(), seller)
        client.getInvoice(target, token, invoice().content.identifier)

        assertEquals(listOf("login/", "addInvoice_confirm/", "getInvoice/"), exchanges.map { it.service })
        assertEquals(listOf(200, 200, 200), exchanges.map { it.statusCode })
        assertTrue("FN-00001/2026" in exchanges[1].requestBody)
        assertTrue("4530253" in exchanges[1].responseBody)
        assertFalse(exchanges.any { token in it.requestBody || token in it.responseBody })
    }
}
