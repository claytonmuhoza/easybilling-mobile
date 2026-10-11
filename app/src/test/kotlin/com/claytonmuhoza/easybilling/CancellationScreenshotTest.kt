package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationKind
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationState
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellation
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.invoices.CancelInvoiceActions
import com.claytonmuhoza.easybilling.invoices.CancelInvoiceScreen
import com.claytonmuhoza.easybilling.invoices.CancelInvoiceUiState
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailActions
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailScreen
import com.claytonmuhoza.easybilling.invoices.InvoiceDetailUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the cancellation of an invoice and of what an invoice says of it (UC-E01, E02),
 * in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CancellationScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val invoice = TransmissionSamples.invoice(48, price = "124000")
    private val receipt = ObrReceipt("4530253", "2026-10-03 10:15:44", "signature", "{}")

    private fun cancellation(state: CancellationState, kind: CancellationKind = CancellationKind.CANCEL) =
        InvoiceCancellation(
            invoiceId = invoice.id,
            kind = kind,
            reason = "Erreur de saisie du client",
            requestedBy = UserId(1),
            approvedBy = null,
            requestedAt = TransmissionSamples.NOW,
            state = state,
            replacement = InvoiceId(49).takeIf { kind == CancellationKind.REPLACE },
        )

    @Composable
    private fun detail(state: InvoiceDetailUiState.Ready) = InvoiceDetailScreen(state, InvoiceDetailActions())

    @Composable
    private fun cancel(state: CancelInvoiceUiState) = CancelInvoiceScreen(state, CancelInvoiceActions())

    @Test
    fun cancelForm() = capture("invoice_cancel") {
        cancel(CancelInvoiceUiState.Form(number = invoice.content.number, reason = "Erreur de saisie du client"))
    }

    @Test
    fun cancelFormProblems() = capture("invoice_cancel_problems") {
        cancel(CancelInvoiceUiState.Form(number = invoice.content.number, reasonProblem = ReasonProblem.BLANK))
    }

    @Test
    fun cancelRefused() = capture("invoice_cancel_refused") {
        cancel(
            CancelInvoiceUiState.Form(
                number = invoice.content.number,
                reason = "Erreur",
                refusal = CancellationProblem.CANCELLING,
            ),
        )
    }

    @Test
    fun cancelNotCancellable() = capture("invoice_cancel_not_cancellable") {
        cancel(CancelInvoiceUiState.NotCancellable(CancellationProblem.REJECTED))
    }

    @Test
    fun detailCancellable() = capture("invoice_detail_cancellable") {
        detail(InvoiceDetailUiState.Ready(invoice, InvoiceStatus.Registered, receipt, cancellable = true))
    }

    @Test
    fun detailCancellationRequested() = capture("invoice_detail_cancellation_requested") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = invoice,
                status = InvoiceStatus.Registered,
                receipt = receipt,
                cancellation = cancellation(CancellationState.Requested),
            ),
        )
    }

    @Test
    fun detailCancellationRefused() = capture("invoice_detail_cancellation_refused") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = invoice,
                status = InvoiceStatus.Registered,
                receipt = receipt,
                cancellation = cancellation(CancellationState.Refused("Identifiant de la facture inconnu")),
                cancellable = true,
            ),
        )
    }

    @Test
    fun detailReplaced() = capture("invoice_detail_replaced") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = invoice,
                status = InvoiceStatus.Cancelled,
                receipt = receipt,
                cancellation = cancellation(CancellationState.Done, CancellationKind.REPLACE),
                replacedBy = "FN-00049/2026",
            ),
        )
    }

    @Test
    fun detailReplacement() = capture("invoice_detail_replacement") {
        val replacement = invoice.copy(content = invoice.content.copy(cancelledInvoiceRef = "FN-00047/2026"))
        detail(InvoiceDetailUiState.Ready(replacement, InvoiceStatus.Registered, receipt, cancellable = true))
    }

    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
