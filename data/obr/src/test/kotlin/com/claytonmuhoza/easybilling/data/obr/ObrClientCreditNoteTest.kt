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
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReason
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
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
 * The credit note (UC-E03) against the simulated OBR, which answers as the server did on
 * 6 October 2026 (`docs/architecture.md` § 7.28).
 */
class ObrClientCreditNoteTest {

    private val simulator = ObrSimulator()
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val client = ObrClient(
        ObrHttp.client(timeout = Duration.ofSeconds(1)),
        ExchangeRecorder { },
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

    private fun document(type: DocumentType, number: String, credit: CreditNote? = null) = Invoice(
        id = InvoiceId(1),
        content = InvoiceContent(
            type = type,
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
            credit = credit,
        ),
    )

    private fun invoice(number: String) = document(DocumentType.FN, number)

    private fun creditNote(number: String, of: String, reason: CreditReason = CreditReason(CreditReasonType.ERROR)) =
        document(DocumentType.FA, number, CreditNote(of, reason))

    @BeforeTest
    fun start() {
        simulator.start()
        val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        target = LoginTarget(EbmsEnvironment.TEST, simulator.baseUrl, credentials)
    }

    @AfterTest
    fun stop() = simulator.close()

    private suspend fun token(): String = assertIs<LoginOutcome.Success>(client.login(target)).token

    private suspend fun sent(document: Invoice): SendOutcome = client.addInvoice(target, token(), document, seller)

    @Test
    fun `a credit note is sent as an FA with the number it credits and its motif, and is acknowledged`() = runTest {
        sent(invoice("FN-00001/2026"))
        val reason = CreditReason(CreditReasonType.GOODS_RETURN, "carton abîmé")

        val outcome = sent(creditNote("FA-00001/2026", of = "FN-00001/2026", reason))

        assertIs<SendOutcome.Accepted>(outcome)
        val body = Json.parseToJsonElement(simulator.requests.last().body!!.utf8()).jsonObject
            .mapValues { (it.value as? JsonPrimitive)?.content }
        assertEquals("FA", body["invoice_type"])
        assertEquals("FN-00001/2026", body["invoice_ref"])
        assertEquals("Retour marchandises : carton abîmé", body["cn_motif"])
        assertEquals("", body["cancelled_invoice_ref"])
        assertEquals("FA-00001/2026", body["invoice_number"])
    }

    @Test
    fun `a credit note without the number it credits is refused`() = runTest {
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_MISSING_INVOICE_REF),
            sent(document(DocumentType.FA, "FA-00001/2026")),
        )
    }

    @Test
    fun `a credit note on an unknown number, on a credit note or on a cancelled invoice is refused`() = runTest {
        val sale = invoice("FN-00001/2026")
        sent(sale)
        sent(creditNote("FA-00001/2026", of = "FN-00001/2026"))

        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_CREDIT_UNKNOWN_INVOICE),
            sent(creditNote("FA-00002/2026", of = "FN-09999/2026")),
        )
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_CREDIT_NOT_AN_INVOICE),
            sent(creditNote("FA-00003/2026", of = "FA-00001/2026")),
        )
        client.cancelInvoice(target, token(), sale.content.identifier, "Erreur")
        assertEquals(
            SendOutcome.Rejected(ObrSimulator.MESSAGE_CREDIT_CANCELLED_INVOICE),
            sent(creditNote("FA-00004/2026", of = "FN-00001/2026")),
        )
    }

    @Test
    fun `the server checks neither the motif nor the total of the credit notes of an invoice`() = runTest {
        sent(invoice("FN-00001/2026"))

        // Twice the whole invoice: the cap is the business of the application (§ 7.28).
        assertIs<SendOutcome.Accepted>(sent(creditNote("FA-00001/2026", of = "FN-00001/2026")))
        assertIs<SendOutcome.Accepted>(sent(creditNote("FA-00002/2026", of = "FN-00001/2026")))
    }
}
