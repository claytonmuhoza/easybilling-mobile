package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.testing.FakeObrGateway
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Checking an invoice at the OBR (UC-C09): what the OBR registered, compared with the device. */
class VerifyInvoiceTest {

    private val fixture = SaleFixture()
    private val gateway = FakeObrGateway()
    private var logins = 0
    private var invalidations = 0
    private var login: () -> LoginOutcome = { LoginOutcome.Success("token-${++logins}") }
    private val tokens = object : TokenProvider {
        override suspend fun token(): LoginOutcome = login()

        override fun invalidate() {
            invalidations++
        }
    }
    private val verify = VerifyInvoice(
        fixture.invoices,
        DispatchConnection({ fixture.ebmsSettings.activeLoginTarget(fixture.vault) }, tokens, gateway),
    )

    /** Issues an invoice of one soap; the OBR has it when [registered]. */
    private suspend fun issued(registered: Boolean = true): Invoice {
        fixture.ready()
        val soap = fixture.item("ART-0001", "1000")
        val draft = fixture.editSale.addItem(soap.id)
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
        if (registered) {
            gateway.accept(invoice)
            fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())
        }
        return invoice
    }

    @Test
    fun `an invoice the OBR has as it was sent shows no difference`() = runTest {
        val invoice = issued()

        val found = assertIs<VerifyInvoice.Result.Found>(verify(invoice.id))

        assertEquals(emptySet(), found.differences)
        assertEquals(invoice.content.number, found.copy.number)
        assertEquals(1, found.copy.itemCount)
        assertEquals(0, BigDecimal("1180").compareTo(found.copy.total))
        assertEquals(listOf(Triple("getInvoice", "token-1", invoice.content.identifier)), gateway.calls)
    }

    @Test
    fun `what differs from the device is told`() = runTest {
        val invoice = issued()
        gateway.copies[invoice.content.identifier] = gateway.copyOf(invoice).copy(
            number = "FN-00009/2026",
            total = BigDecimal("999"),
            cancelled = true,
        )

        val found = assertIs<VerifyInvoice.Result.Found>(verify(invoice.id))

        assertEquals(
            setOf(InvoiceDifference.NUMBER, InvoiceDifference.TOTAL, InvoiceDifference.CANCELLED),
            found.differences,
        )
        // Checking never changes the invoice of the device: the reconciliation will.
        assertEquals(InvoiceStatus.Registered, fixture.invoices.statusOf(invoice.id))
    }

    @Test
    fun `an answer that says neither the total nor the cancellation shows no difference for them`() = runTest {
        val invoice = issued()
        gateway.copies[invoice.content.identifier] = gateway.copyOf(invoice).copy(total = null, cancelled = null)

        assertEquals(emptySet(), assertIs<VerifyInvoice.Result.Found>(verify(invoice.id)).differences)
    }

    @Test
    fun `an invoice the OBR does not have is absent`() = runTest {
        val invoice = issued(registered = false)

        assertEquals(VerifyInvoice.Result.Absent, verify(invoice.id))
        assertEquals(InvoiceStatus.Pending, fixture.invoices.statusOf(invoice.id))
    }

    @Test
    fun `no answer of the OBR concludes nothing`() = runTest {
        val invoice = issued()
        gateway.forcedLookups += LookupOutcome.Unavailable("SocketTimeoutException")

        assertEquals(VerifyInvoice.Result.Unavailable("SocketTimeoutException"), verify(invoice.id))
    }

    @Test
    fun `a refused token is renewed once`() = runTest {
        val invoice = issued()
        gateway.forcedLookups += LookupOutcome.TokenRefused

        assertIs<VerifyInvoice.Result.Found>(verify(invoice.id))

        assertEquals(1, invalidations)
        assertEquals(listOf("token-1", "token-2"), gateway.calls.map { it.second })
    }

    @Test
    fun `without a login nothing is asked`() = runTest {
        val invoice = issued()
        login = { LoginOutcome.Unreachable }

        assertEquals(VerifyInvoice.Result.Unavailable("LOGIN"), verify(invoice.id))
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `an invoice of another environment, or an unknown one, is not asked for`() = runTest {
        val invoice = issued()
        fixture.ebmsSettings.write(fixture.ebmsSettings.read().copy(activeEnvironment = EbmsEnvironment.PRODUCTION))

        assertEquals(VerifyInvoice.Result.NotConfigured, verify(invoice.id))
        assertEquals(VerifyInvoice.Result.UnknownInvoice, verify(InvoiceId(99)))
        assertEquals(emptyList(), gateway.calls)
    }
}
