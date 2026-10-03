package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.testing.percent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sale being typed, its amounts and the sales on hold (UC-C01 steps 1 to 3, UC-C02, UC-C03). */
class SaleDraftTest {

    private val fixture = SaleFixture()
    private val edit = fixture.editSale

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun line(quantity: String, discount: Discount? = null, free: Map<TaxKind, Money> = emptyMap()) =
        SaleLineCheck.Valid(Quantity.of(quantity), discount, free)

    private suspend fun priced() = fixture.priceSale(edit.current())

    @Test
    fun `the first sale starts empty, for the counter customer, paid in cash`() = runTest {
        fixture.ready()

        val sale = priced()

        assertEquals(emptyList(), sale.lines)
        assertTrue(sale.customer.counter)
        assertEquals(PaymentType.CASH, sale.draft.paymentType)
        assertEquals(bif("0"), sale.totals.total)
        assertEquals(setOf(SaleProblem.EMPTY), sale.problems)
        assertFalse(sale.issuable)
        assertEquals(sale.draft, edit.current())
    }

    @Test
    fun `adding an item twice gives one line with a quantity of two`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val sugar = fixture.item("ART-0002", "3000")

        edit.addItem(soap.id)
        edit.addItem(sugar.id)
        val draft = edit.addItem(soap.id)

        val expected = listOf(soap.id to Quantity.of("2"), sugar.id to Quantity.of("1"))
        assertEquals(expected, draft.lines.map { it.itemId to it.quantity })
        assertEquals(draft, fixture.drafts.current())
    }

    @Test
    fun `each line follows the formula of the OBR and the totals are their sums`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val sugar = fixture.item("ART-0002", "2360", PriceMode.INCLUDING_TAXES)
        edit.addItem(soap.id)
        edit.setLine(soap.id, line("2.5"))
        edit.addItem(sugar.id)

        val sale = priced()

        val first = sale.lines[0].amounts!!
        assertEquals(bif("2500"), first.priceWithoutVat)
        assertEquals(bif("450"), first.vat)
        assertEquals(bif("2950"), first.total)
        val second = sale.lines[1].amounts!!
        assertEquals(bif("2000"), second.priceWithoutVat)
        assertEquals(bif("360"), second.vat)
        assertEquals(bif("2360"), second.total)
        assertEquals(bif("4500"), sale.totals.priceWithoutVat)
        assertEquals(bif("810"), sale.totals.vat)
        assertEquals(bif("5310"), sale.totals.total)
        assertEquals(BigDecimal("18"), sale.lines[0].vatRate(sale.registrations))
        assertTrue(sale.issuable)
    }

    @Test
    fun `a discount lowers the unit price before the taxes`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val sugar = fixture.item("ART-0002", "2360", PriceMode.INCLUDING_TAXES)
        edit.addItem(soap.id)
        edit.addItem(sugar.id)

        edit.setLine(soap.id, line("2", Discount.Percent(BigDecimal("10"))))
        edit.setLine(sugar.id, line("1", Discount.PerUnit(BigDecimal("360"))))
        val sale = priced()

        assertEquals(bif("900"), sale.lines[0].netUnitPrice)
        assertEquals(bif("900"), sale.lines[0].amounts!!.unitPrice)
        assertEquals(bif("1800"), sale.lines[0].amounts!!.priceWithoutVat)
        assertEquals(bif("2124"), sale.lines[0].amounts!!.total)
        // A price including taxes is lowered taxes included: the customer pays 2 000.
        assertEquals(bif("2000"), sale.lines[1].netUnitPrice)
        assertEquals(bif("2000"), sale.lines[1].amounts!!.total)
        assertEquals(bif("4124"), sale.totals.total)
    }

    @Test
    fun `a discount that leaves no price prevents the issue`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        edit.addItem(soap.id)

        edit.setLine(soap.id, line("1", Discount.PerUnit(BigDecimal("1000"))))
        val sale = priced()

        assertEquals(setOf(SaleProblem.DISCOUNT_TOO_HIGH), sale.lines.single().problems)
        assertNull(sale.lines.single().amounts)
        assertEquals(bif("0"), sale.totals.total)
        assertFalse(sale.issuable)
    }

    @Test
    fun `a tax typed on the line is taken in the amounts`() = runTest {
        fixture.ready(TaxRegistrations(vat = true, consumptionTax = true))
        val free = fixture.tax(TaxKind.CONSUMPTION_TAX, "TC libre", TaxComputation.FreeAmount)
        val taxes = mapOf(TaxKind.VAT to fixture.vat, TaxKind.CONSUMPTION_TAX to free)
        val beer = fixture.item("ART-0001", "200", taxes = taxes)
        edit.addItem(beer.id)

        edit.setLine(beer.id, line("10", free = mapOf(TaxKind.CONSUMPTION_TAX to bif("30"))))
        val sale = priced()

        // The example of the specification: 10 × 200 + 30 = 2 030, VAT 365.4.
        assertEquals(setOf(TaxKind.CONSUMPTION_TAX), sale.lines.single().freeTaxes)
        assertEquals(bif("2030"), sale.lines.single().amounts!!.priceWithoutVat)
        assertEquals(bif("365.4"), sale.lines.single().amounts!!.vat)
        assertEquals(bif("2395.4"), sale.totals.total)
    }

    @Test
    fun `a price including taxes that does not cover its fixed taxes prevents the issue`() = runTest {
        fixture.ready(TaxRegistrations(vat = true, consumptionTax = true))
        val free = fixture.tax(TaxKind.CONSUMPTION_TAX, "TC libre", TaxComputation.FreeAmount)
        val taxes = mapOf(TaxKind.VAT to fixture.vat, TaxKind.CONSUMPTION_TAX to free)
        val beer = fixture.item("ART-0001", "200", PriceMode.INCLUDING_TAXES, taxes)
        edit.addItem(beer.id)

        edit.setLine(beer.id, line("1", free = mapOf(TaxKind.CONSUMPTION_TAX to bif("500"))))

        assertEquals(setOf(SaleProblem.PRICE_BELOW_TAXES), priced().lines.single().problems)
    }

    @Test
    fun `an item without VAT cannot be sold by a business registered for the VAT`() = runTest {
        fixture.ready()
        val old = fixture.item("ART-0001", "1000", taxes = emptyMap())
        edit.addItem(old.id)

        assertEquals(setOf(SaleProblem.ITEM_WITHOUT_VAT), priced().lines.single().problems)

        fixture.saveProfile(TaxRegistrations())
        val sale = priced()
        assertTrue(sale.issuable)
        assertEquals(bif("1000"), sale.totals.total)
        assertNull(sale.lines.single().vatRate(sale.registrations))
    }

    @Test
    fun `an item or a customer archived since they were chosen prevents the issue`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val customer = fixture.customers.create(Customer.counterDetails("Aline Irakoze"))
        edit.addItem(soap.id)
        edit.setCustomer(customer.id)
        assertEquals("Aline Irakoze", priced().customer.details.name)

        fixture.items.setArchived(soap.id, true)
        fixture.customers.setArchived(customer.id, true)
        val sale = priced()

        assertEquals(setOf(SaleProblem.ITEM_ARCHIVED), sale.lines.single().problems)
        assertEquals(setOf(SaleProblem.CUSTOMER_ARCHIVED), sale.problems)

        edit.setCustomer(null)
        assertTrue(priced().customer.counter)
    }

    @Test
    fun `the rate of the day applies, not a rate scheduled for later`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val later = TaxVersion(LocalDate.of(2026, 10, 10), percent("20", PercentageBase.PRICE_WITHOUT_VAT))
        fixture.taxes.addVersion(fixture.vat.id, later)
        edit.addItem(soap.id)

        assertEquals(bif("180"), priced().totals.vat)
        fixture.time.advance(Duration.ofDays(7))
        assertEquals(bif("200"), priced().totals.vat)
    }

    @Test
    fun `a line is removed, and the payment type is chosen`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        edit.addItem(soap.id)

        assertEquals(PaymentType.CREDIT, edit.setPaymentType(PaymentType.CREDIT).paymentType)
        assertEquals(emptyList(), edit.removeLine(soap.id).lines)
        assertEquals(PaymentType.CREDIT, edit.current().paymentType)
        assertEquals(listOf("1", "2", "3", "4"), PaymentType.entries.map { it.code })
    }

    @Test
    fun `a sale put on hold leaves the cash register and is resumed later`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val sugar = fixture.item("ART-0002", "3000")
        val first = edit.addItem(soap.id)

        assertTrue(edit.hold())

        assertEquals(listOf(first.id), fixture.drafts.held().map { it.id })
        val second = edit.addItem(sugar.id)
        assertTrue(second.id != first.id)
        assertEquals(listOf(sugar.id), second.lines.map { it.itemId })

        assertTrue(edit.resume(first.id))

        assertEquals(first.id, edit.current().id)
        assertEquals(listOf(soap.id), edit.current().lines.map { it.itemId })
        // The sale that was being typed took its place on hold.
        assertEquals(listOf(second.id), fixture.drafts.held().map { it.id })
    }

    @Test
    fun `an empty sale is not put on hold, and is dropped when another one is resumed`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        assertFalse(edit.hold())

        val held = edit.addItem(soap.id)
        edit.hold()
        val empty = edit.current()
        edit.resume(held.id)

        assertEquals(emptyList(), fixture.drafts.held())
        assertNull(fixture.drafts.find(empty.id))
        assertFalse(edit.resume(empty.id))
    }

    @Test
    fun `a sale on hold is discarded without leaving a trace`() = runTest {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val held = edit.addItem(soap.id)
        edit.hold()

        edit.discard(held.id)

        assertEquals(emptyList(), fixture.drafts.held())
        assertEquals(emptyList(), fixture.audit.events)
    }
}
