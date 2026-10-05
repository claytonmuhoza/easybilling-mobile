package com.claytonmuhoza.easybilling.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.transmission.ObrInvoiceCopy
import com.claytonmuhoza.easybilling.domain.transmission.VerifyInvoice
import com.claytonmuhoza.easybilling.ui.decimalText

/** What the OBR acknowledged, the identifier printed on the invoice, and the check on request. */
@Composable
internal fun ObrCard(state: InvoiceDetailUiState.Ready, onVerify: () -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(
                text = stringResource(R.string.invoice_obr_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val receipt = state.receipt
            if (receipt == null) {
                Help(stringResource(R.string.invoice_no_receipt))
            } else {
                Fact(stringResource(R.string.invoice_registered_number), receipt.registeredNumber)
                Fact(stringResource(R.string.invoice_registered_date), receipt.registeredDate)
            }
            Text(
                text = stringResource(R.string.invoice_issued_identifier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Printed on every invoice: shown in full, in fixed-width figures.
            Text(text = state.invoice.content.identifier, fontFamily = FontFamily.Monospace)
            val running = state.verification == VerificationUiState.Running
            OutlinedButton(onClick = onVerify, enabled = !running) {
                AppIconImage(AppIcon.CLOUD, contentDescription = null)
                Text(
                    text = stringResource(if (running) R.string.invoice_verify_running else R.string.invoice_verify),
                    modifier = Modifier.padding(start = Spacing.small),
                )
            }
            (state.verification as? VerificationUiState.Done)?.let { Verification(it.result, state) }
        }
    }
}

/** What the OBR answered. A difference is told, never acted on: the device is not changed. */
@Composable
private fun Verification(result: VerifyInvoice.Result, state: InvoiceDetailUiState.Ready) {
    when (result) {
        is VerifyInvoice.Result.Found -> {
            if (result.differences.isEmpty()) {
                StatusBanner(StatusTone.SUCCESS, stringResource(R.string.invoice_verify_same))
            } else {
                StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_verify_different))
                result.differences.forEach { difference ->
                    Text(
                        text = stringResource(DIFFERENCE_MESSAGES.getValue(difference)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            ObrCopy(result.copy)
        }
        VerifyInvoice.Result.Absent -> StatusBanner(
            // An invoice still to send is expected to be unknown; an acknowledged one is not.
            tone = if (state.status.kind == InvoiceStatusKind.PENDING) StatusTone.PENDING else StatusTone.ERROR,
            text = stringResource(R.string.invoice_verify_absent),
        )
        is VerifyInvoice.Result.Unavailable ->
            StatusBanner(StatusTone.PENDING, stringResource(R.string.invoice_verify_unavailable))
        VerifyInvoice.Result.NotConfigured ->
            StatusBanner(StatusTone.PENDING, stringResource(R.string.invoice_verify_not_configured))
        VerifyInvoice.Result.UnknownInvoice ->
            StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_not_found))
    }
}

/** The invoice as the OBR registered it: only what its answer says. */
@Composable
private fun ObrCopy(copy: ObrInvoiceCopy) {
    val unknown = stringResource(R.string.invoice_copy_unknown)
    Fact(stringResource(R.string.invoice_copy_number), copy.number)
    Fact(stringResource(R.string.invoice_copy_date), copy.date)
    Fact(stringResource(R.string.invoice_copy_customer), copy.customerName)
    Fact(stringResource(R.string.invoice_copy_items), copy.itemCount.toString())
    Fact(
        label = stringResource(R.string.invoice_copy_total),
        value = copy.total?.let { decimalText(it) } ?: unknown,
    )
    Fact(
        label = stringResource(R.string.invoice_copy_cancelled),
        value = when (copy.cancelled) {
            true -> stringResource(R.string.yes)
            false -> stringResource(R.string.no)
            null -> unknown
        },
    )
}
