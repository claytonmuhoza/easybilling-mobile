package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.CreditProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReason
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.transmission.LocalRefusals
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.invoices.CreditIssueProblem
import com.claytonmuhoza.easybilling.invoices.CreditLineUi
import com.claytonmuhoza.easybilling.invoices.CreditNoteActions
import com.claytonmuhoza.easybilling.invoices.CreditNoteScreen
import com.claytonmuhoza.easybilling.invoices.CreditNoteUiState
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
import java.math.BigDecimal

/**
 * Screenshots of the credit note being typed and of what an invoice and a credit note say of each
 * other (UC-E03), in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CreditNoteScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val invoice = TransmissionSamples.invoice(48, price = "124000")
    private val receipt = ObrReceipt("4530253", "2026-10-03 10:15:44", "signature", "{}")

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private val soap = CreditLineUi(
        itemId = 1,
        designation = "Savon de Marseille",
        sold = BigDecimal("10"),
        remaining = BigDecimal("4"),
        includingTaxes = false,
        selected = true,
        quantity = "2",
        unitPrice = "1000",
        total = bif("2360"),
    )
    private val rice = CreditLineUi(
        itemId = 2,
        designation = "Riz 25 kg",
        sold = BigDecimal("3"),
        remaining = BigDecimal("3"),
        includingTaxes = true,
        selected = false,
        quantity = "3",
        unitPrice = "85000",
    )
    private val sugar = CreditLineUi(
        itemId = 3,
        designation = "Sucre 1 kg",
        sold = BigDecimal("5"),
        remaining = BigDecimal.ZERO,
        includingTaxes = false,
        selected = false,
        quantity = "0",
        unitPrice = "3500",
    )

    private fun form(lines: List<CreditLineUi> = listOf(soap, rice, sugar)) = CreditNoteUiState.Form(
        number = invoice.content.number,
        lines = lines,
        remainingAmount = bif("259360"),
        total = bif("2360"),
        reasonType = CreditReasonType.GOODS_RETURN,
        detail = "Carton abîmé",
    )

    private val creditNote = invoice.copy(
        content = invoice.content.copy(
            type = DocumentType.FA,
            number = "FA-00007/2026",
            credit = CreditNote(
                invoiceRef = invoice.content.number,
                reason = CreditReason(CreditReasonType.GOODS_RETURN, "Carton abîmé"),
            ),
        ),
    )

    private fun summary(id: Long, type: DocumentType, number: String, total: String, status: InvoiceStatus) =
        InvoiceSummary(
            id = InvoiceId(id),
            type = type,
            number = number,
            issuedAt = TransmissionSamples.NOW,
            customerName = "Client comptoir",
            paymentType = PaymentType.CASH,
            total = bif(total),
            status = status,
        )

    @Composable
    private fun credit(state: CreditNoteUiState) = CreditNoteScreen(state, CreditNoteActions())

    @Composable
    private fun detail(state: InvoiceDetailUiState.Ready) = InvoiceDetailScreen(state, InvoiceDetailActions())

    @Test
    fun creditForm() = capture("credit_note") { credit(form()) }

    @Test
    fun creditFormProblems() = capture("credit_note_problems") {
        val wrong = soap.copy(
            quantity = "12",
            unitPrice = "0",
            problems = setOf(CreditLineProblem.QUANTITY_TOO_HIGH, CreditLineProblem.PRICE_INVALID),
            total = null,
        )
        credit(
            form(listOf(wrong)).copy(
                total = bif("0"),
                reasonType = null,
                detail = "",
                reasonProblem = ReasonProblem.BLANK,
            ),
        )
    }

    @Test
    fun creditAboveCap() = capture("credit_note_above_cap") {
        credit(
            form(listOf(soap)).copy(
                remainingAmount = bif("1180"),
                aboveCap = true,
                problem = CreditIssueProblem.NUMBERS_EXHAUSTED,
            ),
        )
    }

    @Test
    fun creditNotCreditable() = capture("credit_note_not_creditable") {
        credit(CreditNoteUiState.NotCreditable(CreditProblem.FULLY_CREDITED))
    }

    @Test
    fun creditLoading() = capture("credit_note_loading", animated = true) { credit(CreditNoteUiState.Loading) }

    @Test
    fun creditFailed() = capture("credit_note_failed") { credit(CreditNoteUiState.Failed) }

    @Test
    fun detailCreditable() = capture("invoice_detail_creditable") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = invoice,
                status = InvoiceStatus.Registered,
                receipt = receipt,
                cancellable = true,
                creditable = true,
            ),
        )
    }

    @Test
    fun detailCredited() = capture("invoice_detail_credited") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = invoice,
                status = InvoiceStatus.Registered,
                receipt = receipt,
                creditable = true,
                creditNotes = listOf(
                    summary(49, DocumentType.FA, "FA-00007/2026", "2360", InvoiceStatus.Registered),
                    summary(50, DocumentType.FA, "FA-00008/2026", "1180", InvoiceStatus.Pending),
                ),
            ),
        )
    }

    @Test
    fun detailOfCreditNote() = capture("invoice_detail_credit_note") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = creditNote,
                status = InvoiceStatus.Registered,
                receipt = receipt,
                credited = summary(48, DocumentType.FN, invoice.content.number, "146320", InvoiceStatus.Registered),
            ),
        )
    }

    @Test
    fun detailOfRejectedCreditNote() = capture("invoice_detail_credit_note_rejected") {
        detail(
            InvoiceDetailUiState.Ready(
                invoice = creditNote,
                status = InvoiceStatus.Rejected(LocalRefusals.CREDITED_INVOICE_NOT_REGISTERED),
                credited = summary(
                    48,
                    DocumentType.FN,
                    invoice.content.number,
                    "146320",
                    InvoiceStatus.Rejected("NIF du contribuable inconnu."),
                ),
            ),
        )
    }

    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
