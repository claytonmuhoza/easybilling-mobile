package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issuing a sale as an invoice (UC-C01 steps 4 and 5) and the life of the invoice. */
class IssueSaleTest {

    private val fixture = SaleFixture()
    private val edit = fixture.editSale
    private val issue = fixture.issueSale
    private val alice = fixture.alice

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    /**
     * A sale of two soaps at 1 000 and one sugar at 2 360 taxes included.
     *
     * @param soapInStock whether the soap is kept in stock, with enough of it.
     */
    private suspend fun typedSale(soapInStock: Boolean = false): SaleDraft {
        val soap = fixture.item("ART-0001", "1000", stockTracked = soapInStock, stock = "10".takeIf { soapInStock })
        val sugar = fixture.item("ART-0002", "2360", PriceMode.INCLUDING_TAXES)
        edit.addItem(soap.id)
        edit.addItem(soap.id)
        return edit.addItem(sugar.id)
    }

    private suspend fun issued(draft: SaleDraft) = assertIs<IssueSale.Result.Issued>(issue(draft.id, alice)).invoice

    @Test
    fun `a sale is issued with the next number, its identifier and the date to the second`() = runTest {
        fixture.ready()
        fixture.time.instant = Instant.parse("2026-10-03T08:15:42.789Z")

        val invoice = issued(typedSale()).content

        assertEquals(DocumentType.FN, invoice.type)
        assertEquals("FN-00001/2026", invoice.number)
        assertEquals(Instant.parse("2026-10-03T08:15:42Z"), invoice.issuedAt)
        // 08:15:42 in UTC is 10:15:42 in Bujumbura.
        assertEquals("4000000001/ws400000000100001/20261003101542/FN-00001/2026", invoice.identifier)
        assertEquals(EbmsEnvironment.TEST, invoice.environment)
        assertEquals(1, invoice.profileVersion)
        assertEquals(PaymentType.CASH, invoice.paymentType)
        assertEquals(Currency.BIF, invoice.currency)
        assertEquals(alice, invoice.issuedBy)
    }

    @Test
    fun `the invoice copies the lines, their amounts and the rate applied`() = runTest {
        fixture.ready()
        val draft = typedSale(soapInStock = true)
        val discounted = SaleLineCheck.Valid(Quantity.of("2"), Discount.Percent(BigDecimal("10")), emptyMap())
        edit.setLine(draft.lines[0].itemId, discounted)

        val invoice = issued(draft).content

        val soap = invoice.lines[0]
        assertEquals("ART-0001", soap.itemCode)
        assertEquals("Article ART-0001", soap.designation)
        assertEquals("pièce", soap.unit)
        assertTrue(soap.stockTracked)
        assertEquals(Quantity.of("2"), soap.quantity)
        assertEquals(bif("1000"), soap.listUnitPrice)
        assertEquals(Discount.Percent(BigDecimal("10")), soap.discount)
        assertEquals(BigDecimal("18"), soap.vatRate)
        assertEquals(bif("900"), soap.amounts.unitPrice)
        assertEquals(bif("2124"), soap.amounts.total)
        assertEquals(PriceMode.INCLUDING_TAXES, invoice.lines[1].priceMode)
        assertEquals(bif("2360"), invoice.lines[1].amounts.total)
        assertEquals(bif("3800"), invoice.totals.priceWithoutVat)
        assertEquals(bif("684"), invoice.totals.vat)
        assertEquals(bif("4484"), invoice.totals.total)
    }

    @Test
    fun `the invoice is pending, queued for the OBR and audited, and the sale leaves the drafts`() = runTest {
        fixture.ready()
        val draft = typedSale()

        val invoice = issued(draft)

        assertEquals(invoice, fixture.invoices.find(invoice.id))
        assertEquals(invoice, fixture.invoices.findByNumber("FN-00001/2026"))
        assertEquals(InvoiceStatus.Pending, fixture.invoices.statusOf(invoice.id))
        val entry = fixture.outbox.pending().single()
        assertEquals(OutboxOperation.SendInvoice(invoice.id), entry.operation)
        assertEquals(invoice.content.issuedAt, entry.createdAt)
        assertNull(fixture.drafts.find(draft.id))
        assertEquals(AuditEvent.InvoiceIssued(alice, invoice.id, "FN-00001/2026"), fixture.audit.events.single())
        assertEquals("invoice=${invoice.id.value};number=FN-00001/2026", fixture.audit.events.single().details)
        assertEquals(IssueSale.Result.NotFound, issue(draft.id, alice))
    }

    @Test
    fun `numbers follow each other from one invoice to the next`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")

        val numbers = List(3) { issued(edit.addItem(soap.id)).content.number }

        assertEquals(listOf("FN-00001/2026", "FN-00002/2026", "FN-00003/2026"), numbers)
        assertEquals(3, fixture.invoices.count())
    }

    @Test
    fun `the invoice copies the customer, and the counter customer by default`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val company = fixture.customers.create(
            CustomerDetails(
                name = "Brasserie du Lac SA",
                origin = CustomerOrigin.LOCAL,
                personType = TaxpayerType.LEGAL_PERSON,
                tin = VerifiedTin("4000202020", "BRASSERIE DU LAC", fixture.time.now()),
                address = "Bujumbura, Rohero",
                phone = "22 22 22 22",
                vatPayer = true,
            ),
        )

        val anonymous = issued(edit.addItem(soap.id)).content.customer
        edit.addItem(soap.id)
        val named = issued(edit.setCustomer(company.id)).content.customer

        assertEquals(InvoiceCustomer(fixture.customers.counter().id, "Client comptoir", null, "", false), anonymous)
        assertEquals(InvoiceCustomer(company.id, "Brasserie du Lac SA", "4000202020", "Bujumbura, Rohero", true), named)
    }

    @Test
    fun `an issued invoice does not follow the catalogue, the customers nor the profile`() = runTest {
        fixture.ready()
        val draft = typedSale()
        val invoice = issued(draft)

        val soap = fixture.items.findByCode("ART-0001")!!
        fixture.items.update(soap.id, soap.details.copy(designation = "Savon noir", price = bif("5000")))
        val counter = fixture.customers.counter()
        fixture.customers.update(counter.id, counter.details.copy(name = "Passant"))
        fixture.saveProfile(TaxRegistrations())

        val stored = fixture.invoices.find(invoice.id)!!.content
        assertEquals("Article ART-0001", stored.lines[0].designation)
        assertEquals(bif("4720"), stored.totals.total)
        assertEquals("Client comptoir", stored.customer.name)
        assertEquals(1, stored.profileVersion)
    }

    @Test
    fun `nothing is issued while the configuration is not ready, and no number is consumed`() = runTest {
        fixture.ready()
        val draft = typedSale()
        fixture.ebmsSettings.write(EbmsSettings())

        val result = assertIs<IssueSale.Result.NotReady>(issue(draft.id, alice))

        assertEquals(false, result.readiness.steps[ReadinessStep.EBMS_CONNECTION])
        assertEquals(0, fixture.invoices.count())
        assertEquals(0, fixture.numbering.lastCounter(DocumentType.FN, 2026))
        assertEquals(draft, fixture.drafts.find(draft.id))
        assertEquals(emptyList(), fixture.outbox.pending())
        assertEquals(emptyList(), fixture.audit.events)
    }

    @Test
    fun `a sale that cannot be issued says why, and no number is consumed`() = runTest {
        fixture.ready()

        val empty = assertIs<IssueSale.Result.Invalid>(issue(edit.current().id, alice))
        assertEquals(setOf(SaleProblem.EMPTY), empty.sale.problems)

        val soap = fixture.item("ART-0001", "1000")
        val draft = edit.addItem(soap.id)
        fixture.items.setArchived(soap.id, true)
        val archived = assertIs<IssueSale.Result.Invalid>(issue(draft.id, alice))
        assertEquals(setOf(SaleProblem.ITEM_ARCHIVED), archived.sale.lines.single().problems)

        assertEquals(0, fixture.invoices.count())
        assertEquals(0, fixture.numbering.lastCounter(DocumentType.FN, 2026))
    }

    @Test
    fun `a counter that reached its last number issues nothing`() = runTest {
        fixture.ready()
        val settings = assertIs<NumberingCheck.Valid>(NumberingForm().check(null)).settings
        fixture.numbering.setLastCounter(DocumentType.FN, 2026, settings.maxCounter)

        assertEquals(IssueSale.Result.NumbersExhausted, issue(typedSale().id, alice))
        assertEquals(0, fixture.invoices.count())
    }

    @Test
    fun `a sale on hold is issued too`() = runTest {
        fixture.ready()
        val draft = typedSale()
        edit.hold()

        assertEquals("FN-00001/2026", issued(draft).content.number)
        assertEquals(emptyList(), fixture.drafts.held())
    }

    @Test
    fun `an invoice only moves along the allowed statuses`() = runTest {
        val rejected = InvoiceStatus.Rejected("NIF du client n'est pas connu par l'OBR")
        val all = listOf(InvoiceStatus.Pending, InvoiceStatus.Registered, rejected, InvoiceStatus.Cancelled)
        val allowed = setOf(
            InvoiceStatus.Pending to InvoiceStatus.Registered,
            InvoiceStatus.Pending to rejected,
            rejected to InvoiceStatus.Pending,
            InvoiceStatus.Registered to InvoiceStatus.Cancelled,
        )

        for (from in all) {
            for (to in all) {
                assertEquals((from to to) in allowed, from.canBecome(to), "$from -> $to")
            }
        }
        assertEquals(listOf("PENDING", "REGISTERED", "REJECTED", "CANCELLED"), all.map { it.code })

        fixture.ready()
        val invoice = issued(typedSale())
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())
        assertEquals(InvoiceStatus.Registered, fixture.invoices.statusOf(invoice.id))
        assertFalse(InvoiceStatus.Registered.canBecome(InvoiceStatus.Pending))
    }

    @Test
    fun `a user who may not sell issues nothing`() = runTest {
        val events = fixture.audit.events.size
        fixture.access.denied += Permission.SELL

        assertFailsWith<PermissionDeniedException> { fixture.issueSale(DraftId(1), fixture.alice) }

        assertEquals(listOf(fixture.alice to Permission.SELL), fixture.access.required)
        assertEquals(events, fixture.audit.events.size)
    }
}
