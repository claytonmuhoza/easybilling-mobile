package com.claytonmuhoza.easybilling.invoices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType

/** @return the kind of reason in the language of the device; the OBR receives it in French. */
@Composable
fun CreditReasonType.label(): String = stringResource(
    when (this) {
        CreditReasonType.ERROR -> R.string.credit_reason_error
        CreditReasonType.GOODS_RETURN -> R.string.credit_reason_goods_return
        CreditReasonType.REBATE -> R.string.credit_reason_rebate
        CreditReasonType.DISCOUNT -> R.string.credit_reason_discount
        CreditReasonType.VOLUME_REBATE -> R.string.credit_reason_volume_rebate
    },
)

@Composable
internal fun CreditLineProblem.quantityMessage(): String? = when (this) {
    CreditLineProblem.QUANTITY_INVALID -> stringResource(R.string.credit_note_quantity_invalid)
    CreditLineProblem.QUANTITY_TOO_HIGH -> stringResource(R.string.credit_note_quantity_too_high)
    CreditLineProblem.PRICE_INVALID, CreditLineProblem.PRICE_TOO_HIGH -> null
}

@Composable
internal fun CreditLineProblem.priceMessage(): String? = when (this) {
    CreditLineProblem.PRICE_INVALID -> stringResource(R.string.credit_note_price_invalid)
    CreditLineProblem.PRICE_TOO_HIGH -> stringResource(R.string.credit_note_price_too_high)
    CreditLineProblem.QUANTITY_INVALID, CreditLineProblem.QUANTITY_TOO_HIGH -> null
}

@Composable
internal fun CreditProblem.message(): String = stringResource(
    when (this) {
        CreditProblem.NOT_FOUND -> R.string.invoice_not_found
        CreditProblem.NOT_AN_INVOICE -> R.string.credit_note_problem_not_an_invoice
        CreditProblem.REJECTED -> R.string.credit_note_problem_rejected
        CreditProblem.CANCELLED -> R.string.credit_note_problem_cancelled
        CreditProblem.CANCELLING -> R.string.credit_note_problem_cancelling
        CreditProblem.REPLACING -> R.string.invoice_cancel_problem_replacing
        CreditProblem.FULLY_CREDITED -> R.string.credit_note_problem_fully_credited
    },
)

@Composable
internal fun CreditIssueProblem.message(): String = stringResource(
    when (this) {
        CreditIssueProblem.NO_LINE -> R.string.credit_note_no_line
        CreditIssueProblem.NOT_READY -> R.string.credit_note_not_ready
        CreditIssueProblem.NUMBERS_EXHAUSTED -> R.string.credit_note_numbers_exhausted
        CreditIssueProblem.IDENTIFIER_TOO_LONG -> R.string.credit_note_identifier_too_long
        CreditIssueProblem.FAILED -> R.string.invoice_action_failed
    },
)
