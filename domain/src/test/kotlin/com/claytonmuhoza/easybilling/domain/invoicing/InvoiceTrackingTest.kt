package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.FakeAccessControl
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.testing.TaxProfileSamples
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Following the issued invoices (UC-C08, UC-D04): the list by day, sending a refused invoice
 * again and starting its sale over.
 */
class InvoiceTrackingTest {

    private val fixture = SaleFixture()
    private val access = FakeAccessControl()
    private val resend = ResendRejectedInvoice(
            access,
        fixture.invoices,
        fixture.profiles,
        fixture.outbox,
        fixture.audit,
        DirectTransactionRunner,
        fixture.time,
    )
    private val redo = RedoSale(
        fixture.invoices,
        fixture.items,
        fixture.customers,
        fixture.drafts,
        DirectTransactionRunner,
        fixture.time,
    )

    private suspend fun soap(): Item {
        if (fixture.profiles.current() == null) fixture.ready()
        return fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
    }

    /** Issues an invoice of [quantity] soaps. */
    private suspend fun issued(quantity: Int = 1): Invoice {
        val soap = soap()
        fixture.time.advance(Duration.ofSeconds(1))
        repeat(quantity) { fixture.editSale.addItem(soap.id) }
        val draft = fixture.editSale.current()
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
    }

    /** Issues an invoice the OBR then refuses with [message]; the invoice leaves the send queue. */
    private suspend fun rejected(message: String = "Le NIF du contribuable est invalide."): Invoice {
        val invoice = issued()
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Rejected(message), fixture.time.now())
        fixture.outbox.pending().forEach { fixture.outbox.remove(it.id) }
        return invoice
    }

    /** Saves a new version of the tax profile with another NIF. */
    private suspend fun correctTin(tin: String) {
        val form = TaxProfileSamples.form().with(TaxProfileField.TIN, tin)
        val profile = assertIs<TaxProfileCheck.Valid>(form.check()).profile
        val number = checkNotNull(fixture.profiles.current()).number + 1
        fixture.profiles.append(TaxProfileVersion(number, profile, fixture.time.now(), fixture.alice))
    }

    private fun summary(id: Long, issuedAt: String, total: String, status: InvoiceStatus = InvoiceStatus.Registered) =
        InvoiceSummary(
            id = InvoiceId(id),
            type = DocumentType.FN,
            number = "FN-%05d/2026".format(id),
            issuedAt = Instant.parse(issuedAt),
            customerName = "Client comptoir",
            paymentType = PaymentType.CASH,
            total = Money.of(total, Currency.BIF),
            status = status,
        )

    @Test
    fun `a period starts at the beginning of a day in Bujumbura`() {
        // 23:30 in Bujumbura on 3 October.
        val now = Instant.parse("2026-10-03T21:30:00Z")

        assertNull(InvoicePeriod.ALL.since(now))
        assertEquals(Instant.parse("2026-10-02T22:00:00Z"), InvoicePeriod.TODAY.since(now))
        assertEquals(Instant.parse("2026-09-26T22:00:00Z"), InvoicePeriod.LAST_7_DAYS.since(now))
        assertEquals(Instant.parse("2026-09-03T22:00:00Z"), InvoicePeriod.LAST_30_DAYS.since(now))
    }

    @Test
    fun `invoices are grouped by day of Bujumbura, the most recent day first`() {
        val days = listOf(
            summary(3, "2026-10-03T08:00:00Z", "3000"),
            // 00:30 in Bujumbura on 3 October.
            summary(2, "2026-10-02T22:30:00Z", "2000"),
            summary(1, "2026-10-02T10:00:00Z", "1000"),
        ).groupedByDay()

        assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 2)), days.map { it.date })
        assertEquals(listOf(3L, 2L), days[0].invoices.map { it.id.value })
        assertEquals(Money.of("5000", Currency.BIF), days[0].total)
        assertEquals(Money.of("1000", Currency.BIF), days[1].total)
    }

    @Test
    fun `the total of a day leaves out the refused and the cancelled invoices`() {
        val day = listOf(
            summary(4, "2026-10-03T11:00:00Z", "4000", InvoiceStatus.Pending),
            summary(3, "2026-10-03T10:00:00Z", "3000", InvoiceStatus.Cancelled),
            summary(2, "2026-10-03T09:00:00Z", "2000", InvoiceStatus.Rejected("refused")),
            summary(1, "2026-10-03T08:00:00Z", "1000"),
        ).groupedByDay().single()

        assertEquals(4, day.invoices.size)
        assertEquals(Money.of("5000", Currency.BIF), day.total)
    }

    @Test
    fun `no invoice gives no day`() {
        assertEquals(emptyList(), emptyList<InvoiceSummary>().groupedByDay())
    }

    @Test
    fun `the status of an invoice has a kind used to filter`() {
        assertEquals(InvoiceStatusKind.PENDING, InvoiceStatus.Pending.kind)
        assertEquals(InvoiceStatusKind.REGISTERED, InvoiceStatus.Registered.kind)
        assertEquals(InvoiceStatusKind.REJECTED, InvoiceStatus.Rejected("refused").kind)
        assertEquals(InvoiceStatusKind.CANCELLED, InvoiceStatus.Cancelled.kind)
    }

    @Test
    fun `a refused invoice is sent again as it was when the tax profile did not change`() = runTest {
        val invoice = rejected()
        fixture.audit.events.clear()

        val result = resend(invoice.id, fixture.alice)

        assertEquals(ResendRejectedInvoice.Result.Resent(invoice), result)
        assertEquals(InvoiceStatus.Pending, fixture.invoices.statusOf(invoice.id))
        assertEquals(
            listOf<OutboxOperation>(OutboxOperation.SendInvoice(invoice.id)),
            fixture.outbox.pending().map { it.operation },
        )
        assertEquals(AuditEvent.InvoiceResent(fixture.alice, invoice.id, 1), fixture.audit.events.single())
        assertEquals("invoice=${invoice.id.value};profile=1", fixture.audit.events.single().details)
    }

    @Test
    fun `a refused invoice is sent again with the corrected tax profile and its new identifier`() = runTest {
        val invoice = rejected()
        correctTin("4000999999")

        val resent = assertIs<ResendRejectedInvoice.Result.Resent>(resend(invoice.id, fixture.alice)).invoice

        assertEquals(2, resent.content.profileVersion)
        assertEquals(
            invoice.content.identifier.replaceBefore('/', "4000999999"),
            resent.content.identifier,
        )
        assertEquals(resent, fixture.invoices.find(invoice.id))
        // Nothing else of the invoice changes.
        assertEquals(invoice.content.number, resent.content.number)
        assertEquals(invoice.content.issuedAt, resent.content.issuedAt)
        assertEquals(invoice.content.lines, resent.content.lines)
        assertEquals(invoice.content.customer, resent.content.customer)
    }

    @Test
    fun `an invoice that is not refused is not sent again`() = runTest {
        val invoice = issued()
        val queued = fixture.outbox.pending()

        assertEquals(ResendRejectedInvoice.Result.NotRejected, resend(invoice.id, fixture.alice))
        assertEquals(queued, fixture.outbox.pending())
        assertEquals(ResendRejectedInvoice.Result.NotFound, resend(InvoiceId(99), fixture.alice))
    }

    @Test
    fun `an identifier that no longer fits is refused and nothing changes`() = runTest {
        val invoice = rejected()
        // One character more than the 90 the OBR accepts.
        val tooLong = InvoiceIdentifier.MAX_LENGTH - invoice.content.identifier.substringAfter('/').length
        assertEquals(null, InvoiceIdentifier.withTin(invoice.content.identifier, "4".repeat(tooLong)))
        assertEquals(
            "4000999999/" + invoice.content.identifier.substringAfter('/'),
            InvoiceIdentifier.withTin(invoice.content.identifier, "4000999999"),
        )
    }

    @Test
    fun `the sale of a refused invoice is started over with its customer, its lines and its payment`() = runTest {
        val soap = soap()
        val customer = fixture.customers.create(Customer.counterDetails("Ets Kaneza"))
        fixture.editSale.addItem(soap.id)
        fixture.editSale.addItem(soap.id)
        fixture.editSale.setCustomer(customer.id)
        val draft = fixture.editSale.setPaymentType(PaymentType.CREDIT)
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Rejected("refused"), fixture.time.now())

        assertEquals(RedoSale.Result.Started(skippedLines = 0), redo(invoice.id))

        val started = checkNotNull(fixture.drafts.current())
        assertEquals(customer.id, started.customerId)
        assertEquals(PaymentType.CREDIT, started.paymentType)
        assertEquals(listOf(DraftLine(soap.id, Quantity.of(BigDecimal(2)))), started.lines)
        // The refused invoice stays as it is: the new sale will take a new number.
        assertEquals(InvoiceStatus.Rejected("refused"), fixture.invoices.statusOf(invoice.id))
    }

    @Test
    fun `starting a sale over puts the sale being typed on hold`() = runTest {
        val invoice = rejected()
        val typed = fixture.editSale.addItem(soap().id)

        redo(invoice.id)

        assertEquals(listOf(typed.id), fixture.drafts.held().map { it.id })
        assertEquals(1, checkNotNull(fixture.drafts.current()).lines.size)
    }

    @Test
    fun `an archived item and an archived customer are left out of the sale started over`() = runTest {
        val soap = soap()
        val rice = fixture.item("ART-0002", "2500")
        val customer = fixture.customers.create(Customer.counterDetails("Ets Kaneza"))
        fixture.editSale.addItem(soap.id)
        fixture.editSale.addItem(rice.id)
        val draft = fixture.editSale.setCustomer(customer.id)
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
        fixture.items.setArchived(rice.id, archived = true)
        fixture.customers.setArchived(customer.id, archived = true)

        assertEquals(RedoSale.Result.Started(skippedLines = 1), redo(invoice.id))

        val started = checkNotNull(fixture.drafts.current())
        assertNull(started.customerId)
        assertEquals(listOf(soap.id), started.lines.map { it.itemId })
        assertEquals(RedoSale.Result.NotFound, redo(InvoiceId(99)))
    }

    @Test
    fun `a user who may not resolve the invoices does not send a refused one again`() = runTest {
        val invoice = rejected()
        val events = fixture.audit.events.size
        access.denied += Permission.RESOLVE_INVOICES

        assertFailsWith<PermissionDeniedException> { resend(invoice.id, fixture.alice) }

        assertEquals(listOf(fixture.alice to Permission.RESOLVE_INVOICES), access.required)
        assertEquals(events, fixture.audit.events.size)
    }
}
