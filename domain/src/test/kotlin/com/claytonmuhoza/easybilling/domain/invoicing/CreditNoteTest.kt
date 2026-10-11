package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.testing.perUnit
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credit note (UC-E03, D51, D52): which invoices, the reason, the quantities and the amounts
 * left to credit, the amounts of a partial credit, who may, and what is queued for the OBR.
 */
class CreditNoteTest {

    private val fixture = SaleFixture()
    private val alice = fixture.alice

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private suspend fun account(vararg roles: Role): UserId {
        val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
        return fixture.users.create(NewUser("User ${fixture.users.count() + 1}", roles.toSet(), secret)).id
    }

    private suspend fun soap(): Item {
        if (fixture.profiles.current() == null) fixture.ready()
        return fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
    }

    /** Issues an invoice of [quantity] of [item], registered by the OBR, a second after the previous one. */
    private suspend fun registered(item: Item? = null, quantity: String = "3"): Invoice {
        val sold = item ?: soap()
        fixture.time.advance(Duration.ofSeconds(1))
        val draft = fixture.editSale.addItem(sold.id)
        fixture.editSale.setLine(sold.id, SaleLineCheck.Valid(Quantity.of(quantity), null, emptyMap()))
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, alice)).invoice
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())
        return invoice
    }

    private fun Invoice.line(quantity: String, unitPrice: String) =
        CreditLineForm(content.lines.single().itemId, quantity, unitPrice)

    private suspend fun credit(
        invoice: Invoice,
        quantity: String,
        unitPrice: String = "1000",
        type: CreditReasonType? = CreditReasonType.GOODS_RETURN,
        detail: String = "",
        actor: UserId = alice,
        approver: UserId? = null,
    ): IssueCreditNote.Result {
        fixture.time.advance(Duration.ofSeconds(1))
        return fixture.issueCreditNote(
            CreditRequest(invoice.id, listOf(invoice.line(quantity, unitPrice)), type, detail),
            actor,
            approver,
        )
    }

    private suspend fun credited(invoice: Invoice, quantity: String, unitPrice: String = "1000"): Invoice =
        assertIs<IssueCreditNote.Result.Issued>(credit(invoice, quantity, unitPrice)).invoice

    private fun refused(problem: CreditProblem) = IssueCreditNote.Result.Refused(problem)

    @Test
    fun `a credit note is an FA with its own sequence, the customer and the payment of the invoice`() = runTest {
        val invoice = registered()

        val note = credited(invoice, "1")

        assertEquals(DocumentType.FA, note.content.type)
        assertEquals("FA-00001/2026", note.content.number)
        assertTrue(note.content.identifier.endsWith("/FA-00001/2026"))
        val reason = CreditReason(CreditReasonType.GOODS_RETURN)
        assertEquals(CreditNote(invoice.content.number, reason), note.content.credit)
        assertEquals(invoice.content.customer, note.content.customer)
        assertEquals(invoice.content.paymentType, note.content.paymentType)
        assertEquals(alice, note.content.issuedBy)
        assertEquals(InvoiceStatus.Pending, fixture.invoices.statusOf(note.id))
        // The next invoice keeps the sequence of the invoices.
        assertEquals("FN-00002/2026", registered().content.number)
    }

    @Test
    fun `the credit note is queued and audited with the invoice it credits`() = runTest {
        val manager = account(Role.MANAGER)
        val invoice = registered()
        val queuedBefore = fixture.outbox.pending().size

        val result = credit(invoice, "1", actor = manager)

        val note = assertIs<IssueCreditNote.Result.Issued>(result).invoice
        assertEquals(
            OutboxOperation.SendInvoice(note.id),
            fixture.outbox.pending().drop(queuedBefore).single().operation,
        )
        val event = fixture.audit.events.last()
        assertEquals(AuditEvent.CreditNoteIssued(manager, note.id, "FA-00001/2026", invoice.id, null), event)
        assertEquals("CREDIT_NOTE_ISSUED", event.action)
        assertEquals("invoice=${note.id.value};number=FA-00001/2026;credited=${invoice.id.value}", event.details)
    }

    @Test
    fun `a line credited in full at its price copies the amounts of the invoice`() = runTest {
        val invoice = registered()

        val note = credited(invoice, "3")

        assertEquals(invoice.content.lines.single().amounts, note.content.lines.single().amounts)
        assertEquals(bif("3540"), note.content.totals.total)
    }

    @Test
    fun `a part of the quantity is credited with the VAT of the line`() = runTest {
        val invoice = registered()

        val line = credited(invoice, "1").content.lines.single()

        assertEquals(Quantity.of("1"), line.quantity)
        assertEquals(bif("1000"), line.amounts.unitPrice)
        assertEquals(bif("1000"), line.amounts.price)
        assertEquals(bif("1000"), line.amounts.priceWithoutVat)
        assertEquals(bif("180"), line.amounts.vat)
        assertEquals(bif("1180"), line.amounts.priceWithVat)
        assertEquals(bif("1180"), line.amounts.total)
        assertEquals(invoice.content.lines.single().vatRate, line.vatRate)
        assertEquals(invoice.content.lines.single().designation, line.designation)
    }

    @Test
    fun `a lower unit price credits a part of the price of every unit`() = runTest {
        val invoice = registered()

        val amounts = credited(invoice, "3", unitPrice = "400").content.lines.single().amounts

        assertEquals(bif("400"), amounts.unitPrice)
        assertEquals(bif("1200"), amounts.price)
        assertEquals(bif("216"), amounts.vat)
        assertEquals(bif("1416"), amounts.total)
    }

    @Test
    fun `the taxes added before the VAT and the PFL follow the share of the price credited`() = runTest {
        fixture.ready(TaxRegistrations(vat = true, consumptionTax = true, flatRateWithholding = true))
        val consumption = fixture.tax(TaxKind.CONSUMPTION_TAX, "TC", perUnit("50"))
        val withholding = fixture.tax(TaxKind.FLAT_RATE_WITHHOLDING, "PFL", perUnit("10"))
        val beer = fixture.item(
            "ART-0002",
            "1000",
            taxes = mapOf(
                TaxKind.VAT to fixture.vat,
                TaxKind.CONSUMPTION_TAX to consumption,
                TaxKind.FLAT_RATE_WITHHOLDING to withholding,
            ),
        )
        val invoice = registered(beer, quantity = "4")
        // 4 × 1000 + TC 200 = 4200 ; VAT 756 ; 4956 ; PFL 40 ; 4996.
        assertEquals(bif("4996"), invoice.content.totals.total)

        val amounts = credited(invoice, "1").content.lines.single().amounts

        assertEquals(bif("1000"), amounts.price)
        assertEquals(bif("50"), amounts.consumptionTax)
        assertEquals(bif("1050"), amounts.priceWithoutVat)
        assertEquals(bif("189"), amounts.vat)
        assertEquals(bif("1239"), amounts.priceWithVat)
        assertEquals(bif("10"), amounts.flatRateWithholding)
        assertEquals(bif("1249"), amounts.total)
    }

    @Test
    fun `a price including taxes is credited including taxes, and its total is exact`() = runTest {
        soap()
        val bread = fixture.item("ART-0003", "1000", PriceMode.INCLUDING_TAXES)
        val invoice = registered(bread, quantity = "3")

        val amounts = credited(invoice, "1").content.lines.single().amounts

        assertEquals(bif("1000"), amounts.total)
        assertEquals(bif("1000"), amounts.priceWithVat)
        assertEquals(bif("847.46"), amounts.priceWithoutVat)
        assertEquals(bif("152.54"), amounts.vat)
        assertEquals(bif("847.46"), amounts.price)
        assertEquals(bif("847.46"), amounts.unitPrice)
    }

    @Test
    fun `the amounts of a partial credit keep every identity of the formula of the OBR`() = runTest {
        val invoice = registered(quantity = "7")

        val amounts = credited(invoice, "2.5", unitPrice = "333.33").content.lines.single().amounts

        assertEquals(amounts.priceWithoutVat, amounts.price + amounts.consumptionTax + amounts.ott + amounts.tsce)
        assertEquals(amounts.priceWithVat, amounts.priceWithoutVat + amounts.vat)
        assertEquals(amounts.total, amounts.priceWithVat + amounts.flatRateWithholding)
        assertEquals(bif("833.33"), amounts.price)
        assertEquals(bif("150"), amounts.vat)
    }

    @Test
    fun `a quantity is credited once, the quantity left being what the next credit note may take`() = runTest {
        val invoice = registered()
        credited(invoice, "2")

        val creditable = fixture.creditRules.creditable(invoice.id)
        assertEquals(BigDecimal("1"), creditable?.lines?.single()?.remaining?.stripTrailingZeros())
        assertEquals(bif("1180"), creditable?.remainingAmount)

        val tooMuch = assertIs<IssueCreditNote.Result.Invalid>(credit(invoice, "2")).credit
        assertEquals(setOf(CreditLineProblem.QUANTITY_TOO_HIGH), tooMuch.lines.single().problems)
        assertIs<IssueCreditNote.Result.Issued>(credit(invoice, "1"))
        assertEquals(refused(CreditProblem.FULLY_CREDITED), credit(invoice, "1"))
    }

    @Test
    fun `a line credited for a part of its price counts as credited for its quantity`() = runTest {
        val invoice = registered()
        credited(invoice, "3", unitPrice = "400")

        assertEquals(refused(CreditProblem.FULLY_CREDITED), credit(invoice, "1"))
    }

    @Test
    fun `the quantity and the unit price are numbers above zero, the price at most the one invoiced`() = runTest {
        val invoice = registered()

        fun problems(result: IssueCreditNote.Result) =
            assertIs<IssueCreditNote.Result.Invalid>(result).credit.lines.single().problems

        assertEquals(setOf(CreditLineProblem.QUANTITY_INVALID), problems(credit(invoice, "0")))
        assertEquals(setOf(CreditLineProblem.QUANTITY_INVALID), problems(credit(invoice, "abc")))
        assertEquals(setOf(CreditLineProblem.QUANTITY_INVALID), problems(credit(invoice, "1.234")))
        assertEquals(setOf(CreditLineProblem.QUANTITY_TOO_HIGH), problems(credit(invoice, "3.01")))
        assertEquals(setOf(CreditLineProblem.PRICE_INVALID), problems(credit(invoice, "1", unitPrice = "0")))
        assertEquals(setOf(CreditLineProblem.PRICE_INVALID), problems(credit(invoice, "1", unitPrice = "")))
        assertEquals(setOf(CreditLineProblem.PRICE_TOO_HIGH), problems(credit(invoice, "1", unitPrice = "1000.01")))
        assertEquals(
            setOf(CreditLineProblem.QUANTITY_INVALID, CreditLineProblem.PRICE_INVALID),
            problems(credit(invoice, "-1", unitPrice = "x")),
        )
        // A comma is a decimal point.
        assertIs<IssueCreditNote.Result.Issued>(credit(invoice, "1,5", unitPrice = "999,5"))
    }

    @Test
    fun `the price of a discounted line is the price once the discount is taken off`() = runTest {
        val item = soap()
        fixture.time.advance(Duration.ofSeconds(1))
        val draft = fixture.editSale.addItem(item.id)
        fixture.editSale.setLine(
            item.id,
            SaleLineCheck.Valid(Quantity.of("2"), Discount.Percent(BigDecimal("10")), emptyMap()),
        )
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, alice)).invoice

        val creditable = fixture.creditRules.creditable(invoice.id)

        assertEquals(bif("900"), creditable?.lines?.single()?.netUnitPrice)
        val result = credit(invoice, "1", unitPrice = "1000")
        assertEquals(
            setOf(CreditLineProblem.PRICE_TOO_HIGH),
            assertIs<IssueCreditNote.Result.Invalid>(result).credit.lines.single().problems,
        )
    }

    @Test
    fun `a credit note without a line is not issued`() = runTest {
        val invoice = registered()

        val result = fixture.issueCreditNote(
            CreditRequest(invoice.id, emptyList(), CreditReasonType.ERROR, ""),
            alice,
        )

        val credit = assertIs<IssueCreditNote.Result.Invalid>(result).credit
        assertEquals(false, credit.issuable)
        assertEquals(emptyList(), credit.lines)
        assertEquals(emptyList(), fixture.invoices.creditNotesOf(invoice.content.number))
    }

    @Test
    fun `the credit notes of an invoice never exceed its total`() = runTest {
        val invoice = registered()
        val creditable = checkNotNull(fixture.creditRules.creditable(invoice.id))

        // Nothing credited yet: the whole invoice is left.
        assertEquals(invoice.content.totals.total, creditable.remainingAmount)
        val priced = fixture.creditRules.price(creditable, listOf(invoice.line("3", "1000")))
        assertEquals(bif("3540"), priced.total)
        assertEquals(false, priced.aboveCap)
        assertEquals(true, priced.issuable)
        // The same lines against less than their total are above the cap.
        val less = creditable.copy(remainingAmount = bif("3539.99"))
        val above = fixture.creditRules.price(less, listOf(invoice.line("3", "1000")))
        assertEquals(true, above.aboveCap)
        assertEquals(false, above.issuable)
    }

    @Test
    fun `a rejected credit note no longer counts and another one may be issued`() = runTest {
        val invoice = registered()
        val note = credited(invoice, "3")
        assertEquals(refused(CreditProblem.FULLY_CREDITED), credit(invoice, "1"))

        fixture.invoices.setStatus(note.id, InvoiceStatus.Rejected("Refus de l'OBR"), fixture.time.now())

        val creditable = fixture.creditRules.creditable(invoice.id)
        assertEquals(invoice.content.totals.total, creditable?.remainingAmount)
        assertEquals("FA-00002/2026", credited(invoice, "3").content.number)
    }

    @Test
    fun `only an invoice that is neither rejected, cancelled nor being cancelled is credited`() = runTest {
        val creditedOnce = registered()
        val pending = pending()
        fixture.invoices.setStatus(pending.id, InvoiceStatus.Rejected("NIF invalide"), fixture.time.now())
        val cancelled = registered()
        fixture.invoices.setStatus(cancelled.id, InvoiceStatus.Cancelled, fixture.time.now())
        val cancelling = registered()
        fixture.cancelInvoice(cancelling.id, "Erreur", alice)
        val replacing = registered()
        fixture.replaceInvoice(replacing.id, "Mauvais client", alice)
        val note = credited(creditedOnce, "1")

        assertEquals(refused(CreditProblem.REJECTED), credit(pending, "1"))
        assertEquals(refused(CreditProblem.CANCELLED), credit(cancelled, "1"))
        assertEquals(refused(CreditProblem.CANCELLING), credit(cancelling, "1"))
        assertEquals(refused(CreditProblem.REPLACING), credit(replacing, "1"))
        assertEquals(refused(CreditProblem.NOT_AN_INVOICE), credit(note, "1"))
        assertEquals(
            refused(CreditProblem.NOT_FOUND),
            fixture.issueCreditNote(CreditRequest(InvoiceId(99), emptyList(), CreditReasonType.ERROR, ""), alice),
        )
    }

    /** Issues an invoice of three soaps that the OBR did not answer yet. */
    private suspend fun pending(): Invoice {
        val item = soap()
        fixture.time.advance(Duration.ofSeconds(1))
        val draft = fixture.editSale.addItem(item.id)
        fixture.editSale.setLine(item.id, SaleLineCheck.Valid(Quantity.of("3"), null, emptyMap()))
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, alice)).invoice
    }

    @Test
    fun `an invoice the OBR did not answer yet is credited, the credit note leaving after it`() = runTest {
        val invoice = pending()

        val note = credited(invoice, "1")

        assertEquals(
            listOf(OutboxOperation.SendInvoice(invoice.id), OutboxOperation.SendInvoice(note.id)),
            fixture.outbox.pending().map { it.operation },
        )
    }

    @Test
    fun `the reason has a type and a detail that fits with it in 100 characters`() = runTest {
        val invoice = registered()

        assertEquals(
            IssueCreditNote.Result.InvalidReason(ReasonProblem.BLANK),
            credit(invoice, "1", type = null),
        )
        val longest = CreditReason.maxDetailLength(CreditReasonType.GOODS_RETURN)
        assertEquals(
            IssueCreditNote.Result.InvalidReason(ReasonProblem.TOO_LONG),
            credit(invoice, "1", detail = "x".repeat(longest + 1)),
        )

        val note = assertIs<IssueCreditNote.Result.Issued>(
            credit(invoice, "1", detail = "  " + "x".repeat(longest) + " "),
        ).invoice
        val motif = checkNotNull(note.content.credit).reason.motif
        assertEquals("Retour marchandises : " + "x".repeat(longest), motif)
        assertEquals(CreditReason.MAX_LENGTH, motif.length)
    }

    @Test
    fun `the motif names the type in French, followed by the detail when there is one`() {
        assertEquals("Erreur", CreditReason(CreditReasonType.ERROR).motif)
        assertEquals("Retour marchandises", CreditReason(CreditReasonType.GOODS_RETURN).motif)
        assertEquals("Rabais", CreditReason(CreditReasonType.REBATE).motif)
        assertEquals("Remise", CreditReason(CreditReasonType.DISCOUNT).motif)
        assertEquals("Ristourne", CreditReason(CreditReasonType.VOLUME_REBATE).motif)
        assertEquals(
            "Retour marchandises : carton abîmé",
            CreditReason(CreditReasonType.GOODS_RETURN, "carton abîmé").motif,
        )
    }

    @Test
    fun `a cashier credits only with the approval of a manager`() = runTest {
        val cashier = account(Role.CASHIER)
        val other = account(Role.CASHIER)
        val manager = account(Role.MANAGER)
        val invoice = registered()
        // Nobody holds the right in person: only an approval lets the credit note through.
        fixture.access.denied += Permission.CORRECT_INVOICES

        assertFailsWith<PermissionDeniedException> { credit(invoice, "1", actor = cashier) }
        assertFailsWith<PermissionDeniedException> { credit(invoice, "1", actor = cashier, approver = other) }
        assertFailsWith<PermissionDeniedException> { credit(invoice, "1", actor = manager, approver = manager) }
        assertEquals(emptyList(), fixture.invoices.creditNotesOf(invoice.content.number))

        val note = assertIs<IssueCreditNote.Result.Issued>(
            credit(invoice, "1", actor = cashier, approver = manager),
        ).invoice
        assertEquals(cashier, note.content.issuedBy)
        val event = fixture.audit.events.last()
        assertEquals(AuditEvent.CreditNoteIssued(cashier, note.id, "FA-00001/2026", invoice.id, manager), event)
        assertTrue(event.details.endsWith(";approver=${manager.value}"))
    }

    @Test
    fun `nothing is issued and no number is consumed while the configuration is not complete`() = runTest {
        val invoice = registered()
        fixture.vault.credentials.clear()

        val result = credit(invoice, "1")

        assertIs<IssueCreditNote.Result.NotReady>(result)
        assertNull(fixture.invoices.findByNumber("FA-00001/2026"))
    }

    @Test
    fun `an invoice with a credit note is neither cancelled nor replaced, and a credit note is not cancelled`() =
        runTest {
            val invoice = registered()
            val note = credited(invoice, "1")

            assertEquals(
                CancelInvoice.Result.Refused(CancellationProblem.CREDITED),
                fixture.cancelInvoice(invoice.id, "Erreur", alice),
            )
            assertEquals(
                ReplaceInvoice.Result.Refused(CancellationProblem.CREDITED),
                fixture.replaceInvoice(invoice.id, "Erreur", alice),
            )
            fixture.invoices.setStatus(note.id, InvoiceStatus.Registered, fixture.time.now())
            assertEquals(
                CancelInvoice.Result.Refused(CancellationProblem.CREDIT_NOTE),
                fixture.cancelInvoice(note.id, "Erreur", alice),
            )

            // Once its only credit note is rejected, the invoice may be cancelled again.
            val other = registered()
            val refusedNote = credited(other, "1")
            fixture.invoices.setStatus(refusedNote.id, InvoiceStatus.Rejected("Refus"), fixture.time.now())
            assertEquals(CancelInvoice.Result.Requested, fixture.cancelInvoice(other.id, "Erreur", alice))
        }
}
