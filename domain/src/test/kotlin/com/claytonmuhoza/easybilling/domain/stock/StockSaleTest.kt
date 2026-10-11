package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.invoicing.CancelInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineForm
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
import com.claytonmuhoza.easybilling.domain.invoicing.CreditRequest
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueCreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.ReplaceInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.ResendRejectedInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineCheck
import com.claytonmuhoza.easybilling.domain.invoicing.SaleProblem
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.FakeObrGateway
import com.claytonmuhoza.easybilling.domain.testing.StockFixture
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The stock and the sale (UC-F06, F09, E01, E06, D53): a sale takes its items out of the stock and
 * never more than the stock; a cancelled invoice and goods brought back put them back; nothing of
 * an invoice the OBR refused is declared.
 */
class StockSaleTest {

    private val fixture = StockFixture()
    private val sale = fixture.sale
    private val gateway = FakeObrGateway()
    private val dispatch = fixture.dispatcher(gateway)

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    /** A soap at 1 000 without taxes, 1 180 with the VAT, with [stock] in stock. */
    private suspend fun soap(stock: String? = "10"): Item {
        if (sale.profiles.current() == null) sale.ready()
        return sale.items.findByCode("ART-0001") ?: sale.item("ART-0001", "1000", stockTracked = true, stock = stock)
    }

    private suspend fun type(item: Item, quantity: String, discount: Discount? = null) {
        sale.editSale.addItem(item.id)
        sale.editSale.setLine(item.id, SaleLineCheck.Valid(Quantity.of(quantity), discount, emptyMap()))
    }

    private suspend fun issue(): IssueSale.Result = sale.issueSale(sale.editSale.current().id, sale.alice)

    private suspend fun sold(quantity: String, item: Item? = null): Invoice {
        type(item ?: soap(), quantity)
        return assertIs<IssueSale.Result.Issued>(issue()).invoice
    }

    private suspend fun stockOf(item: Item) = sale.stock.quantityOf(item.id)

    private suspend fun movementsOf(invoice: Invoice) = sale.stock.ofInvoice(invoice.id)

    private fun declared() = gateway.calls.filter { it.first == "addStockMovement" }.map { it.third }

    private fun assertStock(expected: String, item: Item, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Stock of ${item.code}: $actual")

    @Test
    fun `a sale takes its items out of the stock, at the unit price paid, after its invoice`() = runTest {
        val soap = soap()
        type(soap, "3", Discount.Percent(BigDecimal("10")))

        val invoice = assertIs<IssueSale.Result.Issued>(issue()).invoice

        val exit = movementsOf(invoice).single().content
        assertEquals(MovementType.SN, exit.type)
        assertEquals(soap.id, exit.itemId)
        assertEquals("ART-0001", exit.itemCode)
        assertEquals(BigDecimal("3"), exit.quantity.value)
        // 900 without taxes, 1 062 with the VAT of 18 %.
        assertEquals(bif("1062"), exit.unitCost)
        assertEquals("Facture ${invoice.content.number}", exit.description)
        // The specification keeps the reference of the invoice for goods brought back.
        assertNull(exit.invoiceRef)
        assertEquals(invoice.id, exit.invoiceId)
        assertEquals(invoice.content.issuedAt, exit.occurredAt)
        assertEquals(sale.alice, exit.recordedBy)
        assertStock("7", soap, stockOf(soap))
        val queued = sale.outbox.pending().map { it.operation }
        val movement = movementsOf(invoice).single()
        val expected = listOf(OutboxOperation.SendInvoice(invoice.id), OutboxOperation.SendStockMovement(movement.id))
        assertEquals(expected, queued)

        dispatch()

        assertEquals(listOf("addInvoice", "addStockMovement"), gateway.calls.map { it.first }.filter { it != "login" })
        assertEquals(MovementStatus.Declared, sale.stock.statusOf(movement.id))
    }

    @Test
    fun `an item that is not kept in stock is sold without any movement`() = runTest {
        soap()
        val service = sale.item("SRV-0001", "5000", stockTracked = false)

        val invoice = sold("40", service)

        assertEquals(emptyList(), movementsOf(invoice))
        assertEquals(listOf(OutboxOperation.SendInvoice(invoice.id)), sale.outbox.pending().map { it.operation })
    }

    @Test
    fun `a sale never takes more than the stock, whoever asks`() = runTest {
        val soap = soap(stock = "2")
        val service = sale.item("SRV-0001", "5000", stockTracked = false)
        type(soap, "3")
        type(service, "3")

        val result = assertIs<IssueSale.Result.Invalid>(issue())

        val lines = result.sale.lines.associateBy { it.item.id }
        assertEquals(setOf(SaleProblem.STOCK_INSUFFICIENT), lines.getValue(soap.id).problems)
        assertStock("2", soap, checkNotNull(lines.getValue(soap.id).available))
        assertEquals(emptySet(), lines.getValue(service.id).problems)
        assertNull(lines.getValue(service.id).available)
        assertEquals(emptyList(), sale.outbox.pending())
        assertStock("2", soap, stockOf(soap))

        type(soap, "2")
        assertIs<IssueSale.Result.Issued>(issue())
        assertStock("0", soap, stockOf(soap))
    }

    @Test
    fun `an item without any entry in stock cannot be sold`() = runTest {
        val soap = soap(stock = null)
        type(soap, "1")

        val result = assertIs<IssueSale.Result.Invalid>(issue())

        assertEquals(setOf(SaleProblem.STOCK_INSUFFICIENT), result.sale.lines.single().problems)
    }

    @Test
    fun `nothing of an invoice the OBR refused is declared, and its stock comes back`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        val exit = movementsOf(invoice).single()
        gateway.forcedSends += SendOutcome.Rejected("Le NIF du client est invalide.")

        dispatch()

        assertIs<InvoiceStatus.Rejected>(sale.invoices.statusOf(invoice.id))
        assertEquals(MovementStatus.Voided, sale.stock.statusOf(exit.id))
        assertStock("10", soap, stockOf(soap))
        assertEquals(emptyList(), declared())
        assertEquals(emptyList(), sale.outbox.pending())
        assertEquals(emptyList(), sale.stock.toResolve())
    }

    @Test
    fun `a refused invoice sent again takes its stock again, if the stock still has it`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        val exit = movementsOf(invoice).single()
        gateway.forcedSends += SendOutcome.Rejected("Le NIF du client est invalide.")
        dispatch()

        assertIs<ResendRejectedInvoice.Result.Resent>(sale.resendRejected(invoice.id, sale.alice))

        assertEquals(MovementStatus.Pending, sale.stock.statusOf(exit.id))
        assertStock("6", soap, stockOf(soap))
        dispatch()
        assertEquals(InvoiceStatus.Registered, sale.invoices.statusOf(invoice.id))
        assertEquals(listOf(exit.content.reference), declared())
    }

    @Test
    fun `a refused invoice is not sent again when the stock no longer has its items`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        gateway.forcedSends += SendOutcome.Rejected("Le NIF du client est invalide.")
        dispatch()
        sold("8")

        val result = sale.resendRejected(invoice.id, sale.alice)

        assertEquals(ResendRejectedInvoice.Result.InsufficientStock(setOf(soap.id)), result)
        assertIs<InvoiceStatus.Rejected>(sale.invoices.statusOf(invoice.id))
        assertStock("2", soap, stockOf(soap))
    }

    @Test
    fun `a cancelled invoice puts its items back with an entry that names it`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        dispatch()
        assertStock("6", soap, stockOf(soap))

        assertEquals(CancelInvoice.Result.Requested, sale.cancelInvoice(invoice.id, "Erreur de saisie", sale.alice))
        // The stock only comes back once the OBR cancelled the invoice.
        assertStock("6", soap, stockOf(soap))
        dispatch()

        assertEquals(InvoiceStatus.Cancelled, sale.invoices.statusOf(invoice.id))
        val (exit, back) = movementsOf(invoice)
        assertEquals(MovementType.ER, back.content.type)
        assertEquals(exit.content.quantity, back.content.quantity)
        assertEquals(exit.content.unitCost, back.content.unitCost)
        assertEquals(invoice.content.number, back.content.invoiceRef)
        assertEquals("Annulation de la facture ${invoice.content.number}", back.content.description)
        assertStock("10", soap, stockOf(soap))
        assertEquals(listOf(OutboxOperation.SendStockMovement(back.id)), sale.outbox.pending().map { it.operation })

        dispatch()

        assertEquals(listOf(exit.content.reference, back.content.reference), declared())
    }

    @Test
    fun `the sale that replaces an invoice may sell what that invoice took`() = runTest {
        val soap = soap(stock = "4")
        val invoice = sold("4")
        dispatch()
        assertIs<ReplaceInvoice.Result.Started>(sale.replaceInvoice(invoice.id, "Erreur de quantite", sale.alice))
        type(soap, "3")

        val replacement = assertIs<IssueSale.Result.Issued>(issue()).invoice
        // Until the OBR cancels the first invoice, both exits count.
        assertStock("-3", soap, stockOf(soap))
        dispatch()
        dispatch()

        assertEquals(InvoiceStatus.Registered, sale.invoices.statusOf(replacement.id))
        assertEquals(InvoiceStatus.Cancelled, sale.invoices.statusOf(invoice.id))
        assertStock("1", soap, stockOf(soap))
        assertEquals(emptyList(), sale.outbox.pending())
    }

    @Test
    fun `the sale that replaces an invoice cannot sell more than that invoice and the stock`() = runTest {
        val soap = soap(stock = "4")
        val invoice = sold("3")
        dispatch()
        assertIs<ReplaceInvoice.Result.Started>(sale.replaceInvoice(invoice.id, "Erreur de quantite", sale.alice))
        type(soap, "5")

        val result = assertIs<IssueSale.Result.Invalid>(issue())

        assertEquals(setOf(SaleProblem.STOCK_INSUFFICIENT), result.sale.lines.single().problems)
        assertStock("4", soap, checkNotNull(result.sale.lines.single().available))
    }

    private suspend fun credited(invoice: Invoice, quantity: String, reason: CreditReasonType): Invoice {
        val lines = listOf(CreditLineForm(invoice.content.lines.single().itemId, quantity, "1000"))
        val request = CreditRequest(invoice.id, lines, reason, detail = "")
        return assertIs<IssueCreditNote.Result.Issued>(sale.issueCreditNote(request, sale.alice)).invoice
    }

    @Test
    fun `goods brought back enter the stock with the number of their invoice, after the credit note`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        val exit = movementsOf(invoice).single()
        dispatch()

        val note = credited(invoice, "1", CreditReasonType.GOODS_RETURN)

        val back = movementsOf(note).single()
        assertEquals(MovementType.ER, back.content.type)
        assertEquals(BigDecimal("1"), back.content.quantity.value)
        assertEquals(exit.content.unitCost, back.content.unitCost)
        assertEquals(invoice.content.number, back.content.invoiceRef)
        assertEquals("Retour marchandises, avoir ${note.content.number}", back.content.description)
        assertStock("7", soap, stockOf(soap))
        val queued = sale.outbox.pending().map { it.operation }
        assertEquals(listOf(OutboxOperation.SendInvoice(note.id), OutboxOperation.SendStockMovement(back.id)), queued)

        dispatch()

        assertEquals(listOf(exit.content.reference, back.content.reference), declared())
    }

    @Test
    fun `a credit note that is not for goods brought back leaves the stock alone`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        dispatch()

        val note = credited(invoice, "1", CreditReasonType.entries.first { it != CreditReasonType.GOODS_RETURN })

        assertEquals(emptyList(), movementsOf(note))
        assertStock("6", soap, stockOf(soap))
    }

    @Test
    fun `the entry of a refused credit note is not declared and does not count`() = runTest {
        val soap = soap()
        val invoice = sold("4")
        dispatch()
        val note = credited(invoice, "2", CreditReasonType.GOODS_RETURN)
        gateway.forcedSends += SendOutcome.Rejected("Avoir refuse")

        dispatch()

        assertEquals(MovementStatus.Voided, sale.stock.statusOf(movementsOf(note).single().id))
        assertStock("6", soap, stockOf(soap))
        assertEquals(1, declared().size)
    }

    @Test
    fun `a voided movement only comes back with its invoice`() {
        assertEquals(true, MovementStatus.Pending.canBecome(MovementStatus.Voided))
        assertEquals(true, MovementStatus.Voided.canBecome(MovementStatus.Pending))
        assertEquals(false, MovementStatus.Voided.canBecome(MovementStatus.Declared))
        assertEquals(false, MovementStatus.Declared.canBecome(MovementStatus.Voided))
        assertEquals(false, MovementStatus.ToConfirm.canBecome(MovementStatus.Voided))
        assertEquals(false, MovementStatus.Voided.toResolve)
        assertEquals(false, MovementStatus.Voided.counts)
        assertEquals(true, MovementStatus.Rejected("?").counts)
    }
}
