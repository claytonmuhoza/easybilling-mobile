package com.claytonmuhoza.easybilling.sale

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.SaleProblem
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.decimalText

/** The name of each way to pay. */
internal val PAYMENT_LABELS = mapOf(
    PaymentType.CASH to R.string.payment_cash,
    PaymentType.BANK to R.string.payment_bank,
    PaymentType.CREDIT to R.string.payment_credit,
    PaymentType.OTHER to R.string.payment_other,
)

/** @return the way to pay, in words. */
@Composable
fun PaymentType.label(): String = stringResource(PAYMENT_LABELS.getValue(this))

/** The message of each reason a sale cannot be issued. */
internal val SALE_PROBLEM_MESSAGES = mapOf(
    SaleProblem.EMPTY to R.string.sale_problem_empty,
    SaleProblem.CUSTOMER_ARCHIVED to R.string.sale_problem_customer_archived,
    SaleProblem.ITEM_ARCHIVED to R.string.sale_problem_item_archived,
    SaleProblem.ITEM_WITHOUT_VAT to R.string.sale_problem_item_without_vat,
    SaleProblem.DISCOUNT_TOO_HIGH to R.string.sale_problem_discount_too_high,
    SaleProblem.PRICE_BELOW_TAXES to R.string.sale_problem_price_below_taxes,
    SaleProblem.STOCK_INSUFFICIENT to R.string.sale_problem_stock_insufficient,
)

/** @return the message saying why the sale cannot be issued. */
@Composable
fun SaleProblem.message(): String = stringResource(SALE_PROBLEM_MESSAGES.getValue(this))

/** The message of each broken rule of what is typed for a line. */
internal val LINE_PROBLEM_MESSAGES = mapOf(
    SaleLineProblem.QUANTITY_INVALID to R.string.sale_line_problem_quantity,
    SaleLineProblem.DISCOUNT_INVALID to R.string.sale_line_problem_discount,
    SaleLineProblem.FREE_AMOUNT_INVALID to R.string.sale_line_problem_free_amount,
)

/** The message of each refusal to issue. */
internal val REFUSAL_MESSAGES = mapOf(
    IssueRefusal.NUMBERS_EXHAUSTED to R.string.sale_refusal_numbers,
    IssueRefusal.IDENTIFIER_TOO_LONG to R.string.sale_refusal_identifier,
    IssueRefusal.DISCOUNT_NOT_APPROVED to R.string.sale_refusal_discount_not_approved,
    IssueRefusal.REPLACED_INVOICE_NOT_CANCELLABLE to R.string.sale_refusal_replaced_not_cancellable,
)

/** @return the discount in words: `Discount 10 %`, `Discount 250 BIF per unit`. */
@Composable
fun Discount.label(): String = when (this) {
    is Discount.Percent -> stringResource(R.string.sale_line_discount_percent, decimalText(rate))
    is Discount.PerUnit ->
        stringResource(R.string.sale_line_discount_amount, amountText(Money.of(amount, Currency.BIF)))
}

/** @return what the status of the invoice says, which gives it its colour and its icon. */
fun InvoiceStatus.tone(): StatusTone = when (this) {
    InvoiceStatus.Pending -> StatusTone.PENDING
    InvoiceStatus.Registered -> StatusTone.SUCCESS
    is InvoiceStatus.Rejected -> StatusTone.ERROR
    InvoiceStatus.Cancelled -> StatusTone.NEUTRAL
}

/** @return where the invoice stands with the OBR, in words. */
@Composable
fun InvoiceStatus.label(): String = when (this) {
    InvoiceStatus.Pending -> stringResource(R.string.invoice_status_pending)
    InvoiceStatus.Registered -> stringResource(R.string.invoice_status_registered)
    is InvoiceStatus.Rejected -> stringResource(R.string.invoice_status_rejected, message)
    InvoiceStatus.Cancelled -> stringResource(R.string.invoice_status_cancelled)
}
