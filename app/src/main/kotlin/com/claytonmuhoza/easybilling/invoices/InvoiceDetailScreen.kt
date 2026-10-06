package com.claytonmuhoza.easybilling.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.allowed
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.documents.PdfButtons
import com.claytonmuhoza.easybilling.documents.PrintButton
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.sale.label
import com.claytonmuhoza.easybilling.ui.MoneyText
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.dateTimeText
import com.claytonmuhoza.easybilling.ui.decimalText

/**
 * Detail of an invoice wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenSale called when the sale of the invoice was started over and is to be shown.
 * @param viewModel holder of the invoice state.
 */
@Composable
fun InvoiceDetailRoute(
    onBack: () -> Unit,
    onOpenSale: () -> Unit,
    viewModel: InvoiceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openSale = {
        viewModel.dismissNotice()
        onOpenSale()
    }

    // A sale started over whole is shown at once; with lines left out, the user reads why first.
    val notice = (state as? InvoiceDetailUiState.Ready)?.notice
    LaunchedEffect(notice) {
        if (notice == InvoiceNotice.SaleStarted(skippedLines = 0)) openSale()
    }

    InvoiceDetailScreen(
        state = state,
        actions = InvoiceDetailActions(
            onBack = onBack,
            onVerify = viewModel::verify,
            onResend = viewModel::resend,
            onRedo = viewModel::redo,
            onOpenSale = openSale,
            onPrint = viewModel::print,
            onOpenPdf = viewModel::openPdf,
            onSharePdf = viewModel::sharePdf,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the detail of an invoice.
 *
 * @property onBack called when the user goes back.
 * @property onVerify called when the user asks the OBR for the invoice.
 * @property onResend called when the user sends a refused invoice again.
 * @property onRedo called when the user starts the sale of the invoice over.
 * @property onOpenSale called when the user opens the sale that was started over.
 * @property onPrint called when the user prints the ticket of the invoice.
 * @property onOpenPdf called when the user opens the PDF of the invoice.
 * @property onSharePdf called when the user shares the PDF of the invoice.
 * @property onRetry called when the user asks to read the invoice again.
 */
class InvoiceDetailActions(
    val onBack: () -> Unit = {},
    val onVerify: () -> Unit = {},
    val onResend: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onOpenSale: () -> Unit = {},
    val onPrint: () -> Unit = {},
    val onOpenPdf: () -> Unit = {},
    val onSharePdf: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * An invoice (UC-C08): its status with what it calls for, its ticket to print (UC-G01), its PDF
 * to open or to share (UC-G02, UC-G03), its customer, its lines and its totals, and what the OBR
 * acknowledged. The OBR is asked for its own copy on request (UC-C09), and a refused invoice is
 * sent again or its sale started over (UC-D04).
 *
 * @param state the invoice state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun InvoiceDetailScreen(state: InvoiceDetailUiState, actions: InvoiceDetailActions, modifier: Modifier = Modifier) {
    val title = (state as? InvoiceDetailUiState.Ready)?.invoice?.content?.number
        ?: stringResource(R.string.invoices_title)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            InvoiceDetailUiState.Loading -> LoadingState()
            InvoiceDetailUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoice_load_error),
                onRetry = actions.onRetry,
            )
            InvoiceDetailUiState.NotFound -> ErrorState(message = stringResource(R.string.invoice_not_found))
            is InvoiceDetailUiState.Ready -> FormColumn {
                val invoice = state.invoice.content
                Summary(invoice, state.status.kind)
                PrintButton(actions.onPrint, state.print, enabled = !state.busy)
                PdfButtons(actions.onOpenPdf, actions.onSharePdf, state.pdfProblem, enabled = !state.busy)
                StatusSection(state, actions)
                state.notice?.let { Notice(it, actions.onOpenSale) }
                CustomerCard(invoice.customer)
                LinesCard(invoice)
                ObrCard(state, actions.onVerify)
            }
        }
    }
}

/** The status in one word, the total, and when and how the invoice was paid. */
@Composable
private fun Summary(invoice: InvoiceContent, kind: InvoiceStatusKind) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StatusChip(kind.tone(), kind.label())
        MoneyText(invoice.totals.total, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            text = stringResource(
                R.string.invoice_detail_summary,
                dateTimeText(invoice.issuedAt),
                invoice.paymentType.label(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the status calls for: the message of the OBR and the two ways out of a refusal, or a sentence. */
@Composable
private fun StatusSection(state: InvoiceDetailUiState.Ready, actions: InvoiceDetailActions) {
    when (val status = state.status) {
        is InvoiceStatus.Rejected -> {
            // The message of the OBR is shown as it gave it.
            StatusBanner(StatusTone.ERROR, stringResource(R.string.transmission_obr_reason, status.message))
            if (!allowed(Permission.RESOLVE_INVOICES)) {
                Help(stringResource(R.string.invoice_rejected_ask_manager))
                return
            }
            Help(stringResource(R.string.invoice_rejected_help))
            Button(onClick = actions.onResend, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                AppIconImage(AppIcon.REFRESH, contentDescription = null)
                Text(stringResource(R.string.invoice_resend), modifier = Modifier.padding(start = Spacing.small))
            }
            OutlinedButton(onClick = actions.onRedo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                AppIconImage(AppIcon.CART, contentDescription = null)
                Text(stringResource(R.string.invoice_redo), modifier = Modifier.padding(start = Spacing.small))
            }
        }
        InvoiceStatus.Pending -> Help(stringResource(R.string.invoice_pending_help))
        InvoiceStatus.Cancelled -> Help(stringResource(R.string.invoice_cancelled_help))
        InvoiceStatus.Registered -> Unit
    }
}

/** A sentence that explains, under what it explains. */
@Composable
internal fun Help(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the last action gave. */
@Composable
private fun Notice(notice: InvoiceNotice, onOpenSale: () -> Unit) {
    when (notice) {
        InvoiceNotice.Resent -> StatusBanner(StatusTone.SUCCESS, stringResource(R.string.invoice_resent))
        InvoiceNotice.IdentifierTooLong ->
            StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_identifier_too_long))
        InvoiceNotice.Failed -> StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_action_failed))
        is InvoiceNotice.SaleStarted -> if (notice.skippedLines > 0) {
            val skipped = notice.skippedLines
            StatusBanner(StatusTone.PENDING, pluralStringResource(R.plurals.invoice_redo_skipped, skipped, skipped))
            Button(onClick = onOpenSale, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.invoice_open_sale))
            }
        }
    }
}

@Composable
private fun CustomerCard(customer: InvoiceCustomer) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            leadingContent = { IconTile(AppIcon.PERSON) },
            headlineContent = { Text(customer.name) },
            supportingContent = {
                Column {
                    Text(
                        customer.tin?.let { stringResource(R.string.invoice_customer_tin, it) }
                            ?: stringResource(R.string.invoice_customer_no_tin),
                    )
                    if (customer.vatPayer) Text(stringResource(R.string.invoice_customer_vat_payer))
                    if (customer.address.isNotEmpty()) Text(customer.address)
                }
            },
        )
    }
}

/** The lines and the totals, folded until asked for: the header already gives the totals. */
@Composable
private fun LinesCard(invoice: InvoiceContent) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val totals = invoice.totals
    val count = invoice.lines.size
    TonalCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        CardListItem(
            leadingContent = { IconTile(AppIcon.ITEMS) },
            headlineContent = { Text(pluralStringResource(R.plurals.invoice_lines, count, count)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.invoice_lines_summary,
                        amountText(totals.priceWithoutVat),
                        amountText(totals.vat),
                    ),
                )
            },
            trailingContent = {
                AppIconImage(
                    icon = if (expanded) AppIcon.REMOVE else AppIcon.ADD,
                    contentDescription = stringResource(
                        if (expanded) R.string.invoice_lines_hide else R.string.invoice_lines_show,
                    ),
                )
            },
        )
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                invoice.lines.forEach { Line(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Fact(stringResource(R.string.invoice_total_without_vat), amountText(totals.priceWithoutVat))
                Fact(stringResource(R.string.invoice_total_vat), amountText(totals.vat))
                if (totals.flatRateWithholding.amount.signum() != 0) {
                    Fact(stringResource(R.string.invoice_total_withholding), amountText(totals.flatRateWithholding))
                }
                Fact(stringResource(R.string.invoice_total), amountText(totals.total))
            }
        }
    }
}

/** A line: what was sold, how many at which unit price without taxes, and its total. */
@Composable
private fun Line(line: InvoiceLine) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.designation)
            Text(
                text = stringResource(
                    R.string.invoice_line_quantity,
                    decimalText(line.quantity.value),
                    amountText(line.amounts.unitPrice),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = amountText(line.amounts.total), fontWeight = FontWeight.SemiBold)
    }
}

/** A fact of an invoice: what it is, and its value at the end of the line. */
@Composable
internal fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(text = label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
