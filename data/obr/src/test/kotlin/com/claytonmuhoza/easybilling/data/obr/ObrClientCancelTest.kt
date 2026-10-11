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
import com.claytonmuhoza.easybilling.domain.transmission.CancelOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.LookupOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `cancelInvoice/` and the replacement of an invoice (UC-E01, E02) against the simulated OBR,
 * which answers as the server did on 6 October 2026 (`docs/architecture.md` § 7.28).
 */
class ObrClientCancelTest {

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
        taxes = TaxRegistrations(vat = false),
        fiscalCenter = FiscalCenter.DMC,
        activitySector = "Commerce general",
        legalForm = "SPRL",
    )

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun invoice(number: String, replaces: String? = null) = Invoice(
        id = InvoiceId(1),
        content = InvoiceContent(
            type = DocumentType.FN,
            number = number,
            identifier = "4000000001/ws400000000100001/20261003101542/$number",
            issuedAt = now,
            profileVersion = 1,
            environment = EbmsEnvironment.TEST,
            customer = InvoiceCustomer(CustomerId(1), "Client comptoir", null, "", false),
            paymentType = PaymentType.CASH,
            currency = Currency.BIF,
            lines = listOf(
                InvoiceLine(
                    itemId = ItemId(1),
                    itemCode = "ART-0001",
                    designation = "Savon",
                    unit = "pièce",
                    stockTracked = true,
                    quantity = Quantity.of("1"),
                    listUnitPrice = bif("1000"),
                    priceMode = PriceMode.EXCLUDING_TAXES,
                    discount = null,
                    vatRate = null,
                    amounts = LineAmounts(
                        bif("1000"), bif("1000"), bif("0"), bif("0"), bif("0"),
                        bif("1000"), bif("0"), bif("1000"), bif("0"), bif("1000"),
                    ),
                ),
            ),
            issuedBy = UserId(1),
            cancelledInvoiceRef = replaces,
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

    private suspend fun cancelled(identifier: String): Boolean? =
        assertIs<LookupOutcome.Found>(client.getInvoice(target, token(), identifier)).copy.cancelled

    @Test
    fun `a cancellation sends the identifier and the reason, and the invoice is then read as cancelled`() = runTest {
        val sale = invoice("FN-00001/2026")
        client.addInvoice(target, token(), sale, seller)

        val outcome = client.cancelInvoice(target, token(), sale.content.identifier, "Erreur de saisie")

        assertEquals(CancelOutcome.Cancelled, outcome)
        val request = simulator.requests.last { it.url.encodedPath.endsWith("cancelInvoice/") }
        assertEquals("/ebms_api/cancelInvoice/", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(
            mapOf("invoice_identifier" to sale.content.identifier, "cn_motif" to "Erreur de saisie"),
            body.mapValues { (it.value as JsonPrimitive).content },
        )
        assertEquals(true, cancelled(sale.content.identifier))
    }

    @Test
    fun `cancelling again is a success, as the server answers and as the specification refuses it`() = runTest {
        val sale = invoice("FN-00001/2026")
        client.addInvoice(target, token(), sale, seller)
        client.cancelInvoice(target, token(), sale.content.identifier, "Erreur")

        // The server answers 200 again (§ 7.28).
        assertEquals(CancelOutcome.Cancelled, client.cancelInvoice(target, token(), sale.content.identifier, "Erreur"))
        assertEquals(200, exchanges.last().statusCode)

        // The specification answers 400 « déjà annulée »: it is a success too.
        simulator.asSpecified = true
        assertEquals(CancelOutcome.Cancelled, client.cancelInvoice(target, token(), sale.content.identifier, "Erreur"))
        assertEquals(400, exchanges.last().statusCode)
    }

    @Test
    fun `an unknown invoice is a refusal with the message of the OBR`() = runTest {
        val outcome = client.cancelInvoice(target, token(), "4000000001/ws/20261003101542/X", "Erreur")

        assertEquals(CancelOutcome.Rejected(ObrSimulator.MESSAGE_UNKNOWN_IDENTIFIER_CANCEL), outcome)
    }

    @Test
    fun `a refused token, a server error and a lost answer are told apart`() = runTest {
        val token = token()
        simulator.expireTokens()
        assertEquals(CancelOutcome.TokenRefused, client.cancelInvoice(target, token, "id", "Erreur"))

        val fresh = token()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(CancelOutcome.NotDelivered("HTTP 500"), client.cancelInvoice(target, fresh, "id", "Erreur"))

        simulator.dropNext()
        assertIs<CancelOutcome.Uncertain>(client.cancelInvoice(target, fresh, "id", "Erreur"))
    }

    @Test
    fun `a replacement carries the number it replaces and cancels that invoice`() = runTest {
        val old = invoice("FN-00001/2026")
        client.addInvoice(target, token(), old, seller)

        val outcome = client.addInvoice(target, token(), invoice("FN-00002/2026", replaces = "FN-00001/2026"), seller)

        assertIs<SendOutcome.Accepted>(outcome)
        val body = Json.parseToJsonElement(simulator.requests.last().body!!.utf8()).jsonObject
        assertEquals("FN-00001/2026", (body["cancelled_invoice_ref"] as JsonPrimitive).content)
        assertEquals(true, cancelled(old.content.identifier))
    }

    @Test
    fun `a replacement of an invoice already cancelled, or unknown, is refused`() = runTest {
        val old = invoice("FN-00001/2026")
        client.addInvoice(target, token(), old, seller)
        client.cancelInvoice(target, token(), old.content.identifier, "Erreur")

        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_REPLACED_ALREADY_CANCELLED),
            client.addInvoice(target, token(), invoice("FN-00002/2026", replaces = "FN-00001/2026"), seller),
        )
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_UNKNOWN_REPLACED),
            client.addInvoice(target, token(), invoice("FN-00003/2026", replaces = "FN-09999/2026"), seller),
        )
    }
}
