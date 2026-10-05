package com.claytonmuhoza.easybilling.documents

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
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.document.PrintInvoice
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.ui.storageCall
import javax.inject.Inject

/** Where the printing of a ticket stands, on a screen that shows an invoice. */
sealed interface PrintUiState {
    /** The ticket is being sent to the printer. */
    data object Printing : PrintUiState

    /** The printing ended with [result]. */
    data class Done(val result: PrintInvoice.Result) : PrintUiState
}

/**
 * Prints the ticket of an invoice (UC-G01), for the screens that show an invoice.
 *
 * @param printInvoice prints the ticket and traces it in the audit log.
 * @param sessions gives the user who prints.
 * @param settings tells whether a ticket is printed as soon as a sale is issued.
 */
class InvoicePrintActions @Inject constructor(
    private val printInvoice: PrintInvoice,
    private val sessions: SessionManager,
    private val settings: PrinterSettingsRepository,
) {
    /**
     * @param id the invoice.
     * @return how printing its ticket ended.
     */
    suspend fun print(id: InvoiceId): PrintInvoice.Result {
        val actor = sessions.current.value?.userId
        val unread = PrintInvoice.Result.NotFound
        return if (actor == null) unread else storageCall { printInvoice(id, actor) }.getOrDefault(unread)
    }

    /** @return whether a ticket is printed as soon as a sale is issued: the option is on, with a printer. */
    suspend fun printsAfterSale(): Boolean =
        storageCall { settings.read() }.getOrNull()?.let { it.autoPrint && it.printer != null } == true
}

/**
 * The button that prints the ticket of an invoice, and how the last print ended.
 *
 * @param onPrint called when the user prints.
 * @param state where the printing stands, or null before the first print.
 * @param modifier modifier applied to the button.
 * @param enabled false while something else is being done with the invoice.
 */
@Composable
fun PrintButton(onPrint: () -> Unit, state: PrintUiState?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val printing = state == PrintUiState.Printing
    OutlinedButton(onClick = onPrint, enabled = enabled && !printing, modifier = modifier.fillMaxWidth()) {
        AppIconImage(AppIcon.INVOICE, contentDescription = null)
        Text(
            text = stringResource(if (printing) R.string.print_running else R.string.print_ticket),
            modifier = Modifier.padding(start = Spacing.small),
        )
    }
    when (val result = (state as? PrintUiState.Done)?.result) {
        null -> Unit
        PrintInvoice.Result.Printed -> StatusBanner(StatusTone.SUCCESS, stringResource(R.string.print_done))
        PrintInvoice.Result.NoPrinter -> StatusBanner(StatusTone.PENDING, stringResource(R.string.print_no_printer))
        PrintInvoice.Result.NotFound -> StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_not_found))
        is PrintInvoice.Result.Failed -> PrintFailure(result.outcome)
    }
}

/**
 * Why a ticket or a test page was not printed.
 *
 * @param outcome how the print ended.
 * @param modifier modifier applied to the message.
 */
@Composable
fun PrintFailure(outcome: PrintOutcome, modifier: Modifier = Modifier) {
    when (outcome) {
        PrintOutcome.PRINTED -> StatusBanner(StatusTone.SUCCESS, stringResource(R.string.print_done), modifier)
        PrintOutcome.NO_PRINTER -> StatusBanner(StatusTone.PENDING, stringResource(R.string.print_no_printer), modifier)
        PrintOutcome.BLUETOOTH_OFF ->
            StatusBanner(StatusTone.PENDING, stringResource(R.string.print_bluetooth_off), modifier)
        PrintOutcome.PERMISSION_MISSING ->
            StatusBanner(StatusTone.PENDING, stringResource(R.string.print_permission_missing), modifier)
        PrintOutcome.UNREACHABLE -> StatusBanner(StatusTone.ERROR, stringResource(R.string.print_unreachable), modifier)
    }
}
