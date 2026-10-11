package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The cancellation of an invoice and its replacement (UC-E01, E02, E07, D51): who may, which
 * invoices, the reason, and what is queued for the OBR.
 */
class InvoiceCancellationTest {

    private val fixture = SaleFixture()
    private val alice = fixture.alice

    private suspend fun account(vararg roles: Role): UserId {
        val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
        return fixture.users.create(NewUser("User ${fixture.users.count() + 1}", roles.toSet(), secret)).id
    }

    /** Issues an invoice of one soap, issued by [by], a second after the previous one. */
    private suspend fun issued(by: UserId = alice): Invoice {
        if (fixture.profiles.current() == null) fixture.ready()
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        fixture.time.advance(Duration.ofSeconds(1))
        val draft = fixture.editSale.addItem(soap.id)
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, by)).invoice
    }

    private suspend fun registered(): Invoice = issued().also {
        fixture.invoices.setStatus(it.id, InvoiceStatus.Registered, fixture.time.now())
    }

    @Test
    fun `cancelling records the reason and queues the cancellation after the invoice`() = runTest {
        val manager = account(Role.MANAGER)
        val invoice = issued()

        val result = fixture.cancelInvoice(invoice.id, "  Erreur de saisie ", manager)

        assertEquals(CancelInvoice.Result.Requested, result)
        val cancellation = fixture.cancellations.find(invoice.id)
        assertEquals("Erreur de saisie", cancellation?.reason)
        assertEquals(CancellationState.Requested, cancellation?.state)
        assertEquals(manager, cancellation?.requestedBy)
        assertEquals(
            listOf(OutboxOperation.SendInvoice(invoice.id), OutboxOperation.CancelInvoice(invoice.id)),
            fixture.outbox.pending().map { it.operation },
        )
        assertEquals(AuditEvent.InvoiceCancellationRequested(manager, invoice.id, null), fixture.audit.events.last())
    }

    @Test
    fun `the reason is required and holds 100 characters at most`() = runTest {
        val invoice = registered()

        assertEquals(
            CancelInvoice.Result.InvalidReason(ReasonProblem.BLANK),
            fixture.cancelInvoice(invoice.id, "   ", alice),
        )
        assertEquals(
            CancelInvoice.Result.InvalidReason(ReasonProblem.TOO_LONG),
            fixture.cancelInvoice(invoice.id, "x".repeat(101), alice),
        )
        assertEquals(CancelInvoice.Result.Requested, fixture.cancelInvoice(invoice.id, "x".repeat(100), alice))
    }

    @Test
    fun `a rejected, cancelled or already cancelling invoice is not cancelled again`() = runTest {
        val rejected = issued()
        fixture.invoices.setStatus(rejected.id, InvoiceStatus.Rejected("NIF invalide"), fixture.time.now())
        val cancelled = registered()
        fixture.invoices.setStatus(cancelled.id, InvoiceStatus.Cancelled, fixture.time.now())
        val cancelling = registered()
        fixture.cancelInvoice(cancelling.id, "Erreur", alice)

        assertEquals(refused(CancellationProblem.REJECTED), fixture.cancelInvoice(rejected.id, "Erreur", alice))
        assertEquals(refused(CancellationProblem.CANCELLED), fixture.cancelInvoice(cancelled.id, "Erreur", alice))
        assertEquals(refused(CancellationProblem.CANCELLING), fixture.cancelInvoice(cancelling.id, "Erreur", alice))
        assertEquals(refused(CancellationProblem.NOT_FOUND), fixture.cancelInvoice(InvoiceId(99), "Erreur", alice))
    }

    private fun refused(problem: CancellationProblem) = CancelInvoice.Result.Refused(problem)

    @Test
    fun `a cashier cancels only with the approval of a manager`() = runTest {
        val cashier = account(Role.CASHIER)
        val other = account(Role.CASHIER)
        val manager = account(Role.MANAGER)
        val invoice = registered()
        // Nobody holds the right in person: only an approval lets the cancellation through.
        fixture.access.denied += Permission.CORRECT_INVOICES

        assertFailsWith<PermissionDeniedException> { fixture.cancelInvoice(invoice.id, "Erreur", cashier) }
        assertFailsWith<PermissionDeniedException> { fixture.cancelInvoice(invoice.id, "Erreur", cashier, other) }
        assertFailsWith<PermissionDeniedException> { fixture.cancelInvoice(invoice.id, "Erreur", manager, manager) }
        assertNull(fixture.cancellations.find(invoice.id))

        assertEquals(CancelInvoice.Result.Requested, fixture.cancelInvoice(invoice.id, "Erreur", cashier, manager))
        assertEquals(manager, fixture.cancellations.find(invoice.id)?.approvedBy)
        assertEquals(
            AuditEvent.InvoiceCancellationRequested(cashier, invoice.id, manager),
            fixture.audit.events.last(),
        )
    }

    @Test
    fun `the manager and the administrator are allowed to correct an invoice, the others are not`() {
        val policy = FixedPermissionPolicy
        assertEquals(true, policy.allows(setOf(Role.ADMINISTRATOR), Permission.CORRECT_INVOICES))
        assertEquals(true, policy.allows(setOf(Role.MANAGER), Permission.CORRECT_INVOICES))
        assertEquals(false, policy.allows(setOf(Role.CASHIER), Permission.CORRECT_INVOICES))
        assertEquals(false, policy.allows(setOf(Role.STOREKEEPER), Permission.CORRECT_INVOICES))
    }

    @Test
    fun `replacing starts a sale with the lines of the invoice, the sale typed being put on hold`() = runTest {
        val invoice = registered()
        val soap = invoice.content.lines.single().itemId
        fixture.editSale.addItem(soap)

        val result = fixture.replaceInvoice(invoice.id, "Mauvais client", alice)

        assertEquals(ReplaceInvoice.Result.Started(skippedLines = 0), result)
        val replacing = fixture.editSale.current()
        assertEquals(Replacement(invoice.id, "Mauvais client", approvedBy = null), replacing.replacement)
        assertEquals(invoice.content.lines.map { it.itemId }, replacing.lines.map { it.itemId })
        assertEquals(1, fixture.drafts.held().size)
        assertEquals(AuditEvent.InvoiceReplacementStarted(alice, invoice.id, null), fixture.audit.events.last())
        // Nothing is sent before the replacement is issued.
        val queued = fixture.outbox.pending().map { it.operation }
        assertEquals(emptyList(), queued.filterIsInstance<OutboxOperation.CancelInvoice>())
        assertEquals(refused(CancellationProblem.REPLACING), fixture.cancelInvoice(invoice.id, "Erreur", alice))
    }

    @Test
    fun `the replacement carries the number of the invoice it replaces and no cancellation is queued`() = runTest {
        val invoice = registered()
        fixture.replaceInvoice(invoice.id, "Mauvais client", alice)
        fixture.time.advance(Duration.ofSeconds(1))

        val replacement = assertIs<IssueSale.Result.Issued>(
            fixture.issueSale(fixture.editSale.current().id, alice),
        ).invoice

        assertEquals(invoice.content.number, replacement.content.cancelledInvoiceRef)
        val cancellation = fixture.cancellations.find(invoice.id)
        assertEquals(CancellationKind.REPLACE, cancellation?.kind)
        assertEquals(replacement.id, cancellation?.replacement)
        assertEquals(CancellationState.Requested, cancellation?.state)
        val queued = fixture.outbox.pending().map { it.operation }
        assertEquals(OutboxOperation.SendInvoice(replacement.id), queued.last())
        assertEquals(emptyList(), queued.filterIsInstance<OutboxOperation.CancelInvoice>())
        assertNull(fixture.editSale.current().replacement)
    }

    @Test
    fun `a replacement whose invoice was cancelled meanwhile is not issued`() = runTest {
        val invoice = registered()
        fixture.replaceInvoice(invoice.id, "Mauvais client", alice)
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Cancelled, fixture.time.now())

        val result = fixture.issueSale(fixture.editSale.current().id, alice)

        assertEquals(IssueSale.Result.ReplacedInvoiceNotCancellable(CancellationProblem.CANCELLED), result)
    }
}
