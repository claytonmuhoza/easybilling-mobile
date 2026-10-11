package com.claytonmuhoza.easybilling.invoices

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/**
 * What the document says of the credit notes (UC-E03): for a credit note, the invoice it credits
 * and why; for an invoice, its credit notes and the way to issue one while something is left.
 */
@Composable
internal fun CreditSection(state: InvoiceDetailUiState.Ready, actions: InvoiceDetailActions) {
    state.invoice.content.credit?.let { credit ->
        Help(stringResource(R.string.credit_note_of, credit.invoiceRef))
        state.credited?.let { InvoiceCard(it, actions.onOpenDocument) }
        Help(stringResource(R.string.credit_note_motif, credit.reason.motif))
    }
    if (state.creditNotes.isNotEmpty()) SectionHeader(stringResource(R.string.invoice_credit_notes))
    state.creditNotes.forEach { InvoiceCard(it, actions.onOpenDocument) }
    if (state.creditable) {
        OutlinedButton(onClick = actions.onCreditInvoice, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            AppIconImage(AppIcon.REMOVE, contentDescription = null)
            Text(stringResource(R.string.credit_note_start), modifier = Modifier.padding(start = Spacing.small))
        }
    }
}
