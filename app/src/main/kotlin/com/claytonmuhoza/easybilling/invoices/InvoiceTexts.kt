package com.claytonmuhoza.easybilling.invoices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.InvoicePeriod
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.transmission.InvoiceDifference

/** The name of each status on an invoice: one word, the detail screen says the rest. */
internal val STATUS_LABELS = mapOf(
    InvoiceStatusKind.PENDING to R.string.invoice_status_short_pending,
    InvoiceStatusKind.REGISTERED to R.string.invoice_status_short_registered,
    InvoiceStatusKind.REJECTED to R.string.invoice_status_short_rejected,
    InvoiceStatusKind.CANCELLED to R.string.invoice_status_short_cancelled,
)

/** The name of each status as a filter of the list. */
internal val STATUS_FILTER_LABELS = mapOf(
    InvoiceStatusKind.PENDING to R.string.invoices_filter_pending,
    InvoiceStatusKind.REGISTERED to R.string.invoices_filter_registered,
    InvoiceStatusKind.REJECTED to R.string.invoices_filter_rejected,
    InvoiceStatusKind.CANCELLED to R.string.invoices_filter_cancelled,
)

/** The name of each period. */
internal val PERIOD_LABELS = mapOf(
    InvoicePeriod.ALL to R.string.invoices_period_all,
    InvoicePeriod.TODAY to R.string.invoices_period_today,
    InvoicePeriod.LAST_7_DAYS to R.string.invoices_period_7_days,
    InvoicePeriod.LAST_30_DAYS to R.string.invoices_period_30_days,
)

/** The sentence of each difference between the OBR and the device. */
internal val DIFFERENCE_MESSAGES = mapOf(
    InvoiceDifference.NUMBER to R.string.invoice_difference_number,
    InvoiceDifference.TOTAL to R.string.invoice_difference_total,
    InvoiceDifference.CANCELLED to R.string.invoice_difference_cancelled,
)

/** @return the status in one word. */
@Composable
fun InvoiceStatusKind.label(): String = stringResource(STATUS_LABELS.getValue(this))

/** @return what the status says, which gives it its colour and its icon. */
fun InvoiceStatusKind.tone(): StatusTone = when (this) {
    InvoiceStatusKind.PENDING -> StatusTone.PENDING
    InvoiceStatusKind.REGISTERED -> StatusTone.SUCCESS
    InvoiceStatusKind.REJECTED -> StatusTone.ERROR
    InvoiceStatusKind.CANCELLED -> StatusTone.NEUTRAL
}
