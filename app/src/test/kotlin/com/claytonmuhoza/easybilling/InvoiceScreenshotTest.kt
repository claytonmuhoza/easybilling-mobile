package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoicePeriod
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.groupedByDay
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.transmission.InvoiceDifference
import com.claytonmuhoza.easybilling.domain.transmission.ObrInvoiceCopy
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.VerifyInvoice
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailActions
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailScreen
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailUiState
import com.claytonmuhoza.easybilling.invoices.InvoiceListActions
import com.claytonmuhoza.easybilling.invoices.InvoiceListScreen
import com.claytonmuhoza.easybilling.invoices.InvoiceListUiState
import com.claytonmuhoza.easybilling.invoices.InvoiceNotice
import com.claytonmuhoza.easybilling.invoices.VerificationUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/** Screenshots of the list of the invoices and of the detail of one, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class InvoiceScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = TransmissionSamples.NOW
    private val today = now.atZone(BusinessCalendar.ZONE).toLocalDate()
    private val refusal = "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide"

    private fun summary(
        id: Long,
        total: String,
        status: InvoiceStatus = InvoiceStatus.Registered,
        customer: String = "Client comptoir",
        payment: PaymentType = PaymentType.CASH,
        issuedAt: Instant = now - Duration.ofMinutes(10 * (50 - id)),
    ) = InvoiceSummary(
        id = InvoiceId(id),
        type = DocumentType.FN,
        number = "FN-%05d/2026".format(id),
        issuedAt = issuedAt,
        customerName = customer,
        paymentType = payment,
        total = Money.of(total, Currency.BIF),
        status = status,
    )

    private val invoices = listOf(
        summary(48, "146566"),
        summary(47, "18000", InvoiceStatus.Pending, "Ets Kaneza", PaymentType.CREDIT),
        summary(46, "54900", InvoiceStatus.Rejected(refusal), "Inconnu SA"),
        summary(45, "7500", InvoiceStatus.Cancelled),
        summary(44, "63200", customer = "Boulangerie Iteka", issuedAt = now - Duration.ofHours(15)),
        summary(43, "1180", customer = "Brasserie du Lac SA", issuedAt = now - Duration.ofDays(6)),
    )

    private fun listed(
        shown: List<InvoiceSummary> = invoices,
        query: String = "",
        status: InvoiceStatusKind? = null,
        period: InvoicePeriod = InvoicePeriod.ALL,
        limited: Boolean = false,
    ) = InvoiceListUiState.Ready(
        query = query,
        status = status,
        period = period,
        days = shown.groupedByDay(),
        counts = invoices.groupingBy { it.status.kind }.eachCount(),
        today = today,
        limited = limited,
    )

    @Composable
    private fun list(state: InvoiceListUiState) = InvoiceListScreen(state, InvoiceListActions())

    private val invoice: Invoice = TransmissionSamples.invoice(48, price = "124000").let {
        val customer = InvoiceCustomer(it.content.customer.id, "Ets Kaneza", "4000202020", "Bujumbura, Rohero", true)
        it.copy(content = it.content.copy(customer = customer))
    }

    private val receipt = ObrReceipt("4530253", "2026-10-03 10:15:44", "signature", "{}")

    private val copy = ObrInvoiceCopy(
        number = invoice.content.number,
        date = "2026-10-03 10:15:42",
        customerName = "Ets Kaneza",
        itemCount = 1,
        total = invoice.content.totals.total.amount,
        cancelled = false,
    )

    private fun detail(
        status: InvoiceStatus = InvoiceStatus.Registered,
        receipt: ObrReceipt? = this.receipt.takeIf { status == InvoiceStatus.Registered },
        verification: VerifyInvoice.Result? = null,
        notice: InvoiceNotice? = null,
    ) = InvoiceDetailUiState.Ready(
        invoice = invoice,
        status = status,
        receipt = receipt,
        verification = verification?.let { VerificationUiState.Done(it) },
        notice = notice,
    )

    @Composable
    private fun screen(state: InvoiceDetailUiState) = InvoiceDetailScreen(state, InvoiceDetailActions())

    @Test
    fun invoiceList() = capture("invoices") {
        list(listed())
    }

    @Test
    fun invoiceListFiltered() = capture("invoices_filtered") {
        list(
            listed(
                shown = invoices.filter { it.status.kind == InvoiceStatusKind.REJECTED },
                status = InvoiceStatusKind.REJECTED,
                period = InvoicePeriod.LAST_7_DAYS,
            ),
        )
    }

    @Test
    fun invoiceListNoMatch() = capture("invoices_no_match") {
        list(listed(shown = emptyList(), query = "introuvable"))
    }

    @Test
    fun invoiceListLimited() = capture("invoices_limited") {
        list(listed(shown = invoices.take(2), limited = true))
    }

    @Test
    fun invoiceListEmpty() = capture("invoices_empty") {
        list(InvoiceListUiState.Ready(today = today, anyInvoice = false))
    }

    @Test
    fun invoiceListLoading() = capture("invoices_loading", animated = true) {
        list(InvoiceListUiState.Loading)
    }

    @Test
    fun invoiceListError() = capture("invoices_error") {
        list(InvoiceListUiState.Failed)
    }

    @Test
    fun invoiceRegistered() = capture("invoice_detail_registered") {
        screen(detail())
    }

    @Test
    fun invoiceVerified() = capture("invoice_detail_verified") {
        screen(detail(verification = VerifyInvoice.Result.Found(copy, emptySet())))
    }

    @Test
    fun invoiceVerifiedDifferent() = capture("invoice_detail_verified_different") {
        val different = copy.copy(total = null, cancelled = true)
        screen(detail(verification = VerifyInvoice.Result.Found(different, setOf(InvoiceDifference.CANCELLED))))
    }

    @Test
    fun invoicePending() = capture("invoice_detail_pending") {
        screen(detail(InvoiceStatus.Pending, verification = VerifyInvoice.Result.Absent))
    }

    @Test
    fun invoiceUnreachable() = capture("invoice_detail_unreachable") {
        screen(detail(verification = VerifyInvoice.Result.Unavailable("SocketTimeoutException")))
    }

    @Test
    fun invoiceRejected() = capture("invoice_detail_rejected") {
        screen(detail(InvoiceStatus.Rejected(refusal)))
    }

    @Test
    fun invoiceRejectedSaleStarted() = capture("invoice_detail_rejected_sale_started") {
        screen(detail(InvoiceStatus.Rejected(refusal), notice = InvoiceNotice.SaleStarted(skippedLines = 1)))
    }

    @Test
    fun invoiceResent() = capture("invoice_detail_resent") {
        screen(detail(InvoiceStatus.Pending, notice = InvoiceNotice.Resent))
    }

    @Test
    fun invoiceCancelled() = capture("invoice_detail_cancelled") {
        screen(detail(InvoiceStatus.Cancelled, receipt = receipt))
    }

    @Test
    fun invoiceLoading() = capture("invoice_detail_loading", animated = true) {
        screen(InvoiceDetailUiState.Loading)
    }

    @Test
    fun invoiceError() = capture("invoice_detail_error") {
        screen(InvoiceDetailUiState.Failed)
    }

    @Test
    fun invoiceNotFound() = capture("invoice_detail_not_found") {
        screen(InvoiceDetailUiState.NotFound)
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
