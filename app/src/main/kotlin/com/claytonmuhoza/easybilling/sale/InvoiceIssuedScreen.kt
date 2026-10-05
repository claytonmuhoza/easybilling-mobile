package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.component.icon
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.palette
import com.claytonmuhoza.easybilling.documents.InvoicePdfActions
import com.claytonmuhoza.easybilling.documents.InvoicePrintActions
import com.claytonmuhoza.easybilling.documents.PdfButtons
import com.claytonmuhoza.easybilling.documents.PdfOutcome
import com.claytonmuhoza.easybilling.documents.PrintButton
import com.claytonmuhoza.easybilling.documents.PrintUiState
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.navigation.InvoiceIssuedDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.MoneyText
import com.claytonmuhoza.easybilling.ui.dateTimeText
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Side of the circle that shows the status of the invoice. */
private val OutcomeIconSize = 88.dp

/** State of the screen shown once a sale is issued. */
sealed interface InvoiceIssuedUiState {
    /** The invoice is being read. */
    data object Loading : InvoiceIssuedUiState

    /** The invoice could not be read. */
    data object Failed : InvoiceIssuedUiState

    /** There is no invoice with that identifier. */
    data object NotFound : InvoiceIssuedUiState

    /**
     * The invoice is shown.
     *
     * @property invoice the invoice.
     * @property status where it stands with the OBR.
     * @property receipt the acknowledgement of the OBR, or null while there is none.
     * @property pdfProblem why the PDF was not opened or shared at the last try, or null.
     * @property print where the printing of the ticket stands, or null before the first print.
     * @property busy true while a PDF is created.
     */
    data class Ready(
        val invoice: Invoice,
        val status: InvoiceStatus,
        val receipt: ObrReceipt? = null,
        val pdfProblem: PdfOutcome? = null,
        val print: PrintUiState? = null,
        val busy: Boolean = false,
    ) : InvoiceIssuedUiState
}

/**
 * Reads the invoice that was just issued, follows its transmission to the OBR, and prints its
 * ticket at once when the settings of the printer say so.
 */
@HiltViewModel
class InvoiceIssuedViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val invoices: InvoiceRepository,
    private val receipts: ReceiptArchive,
    private val transmission: TransmissionRunner,
    private val pdfActions: InvoicePdfActions,
    private val printActions: InvoicePrintActions,
) : ViewModel() {

    private val id = InvoiceId(savedStateHandle.toRoute<InvoiceIssuedDestination>().id)

    private val mutableState = MutableStateFlow<InvoiceIssuedUiState>(InvoiceIssuedUiState.Loading)

    /** The state to show. */
    val state: StateFlow<InvoiceIssuedUiState> = mutableState.asStateFlow()

    init {
        transmission.kick()
        // The status is read again each time a pass over the send queue ends.
        viewModelScope.launch { transmission.passes.collect { load() } }
        viewModelScope.launch {
            // Once per sale: coming back to this screen prints nothing again.
            if (savedStateHandle.get<Boolean>(AUTO_PRINTED) != true && printActions.printsAfterSale()) {
                savedStateHandle[AUTO_PRINTED] = true
                state.first { it is InvoiceIssuedUiState.Ready }
                print()
            }
        }
    }

    /** Reads the invoice. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val read = storageCall {
                val invoice = invoices.find(id)
                val status = invoices.statusOf(id)
                if (invoice != null && status != null) {
                    InvoiceIssuedUiState.Ready(invoice, status, receipts.find(id))
                } else {
                    InvoiceIssuedUiState.NotFound
                }
            }.getOrElse { InvoiceIssuedUiState.Failed }
            // A print or a PDF may have ended while the storage was read: what the screen says of
            // them is taken from the state as it is now, not as it was before the reading.
            mutableState.update { shown ->
                if (read is InvoiceIssuedUiState.Ready && shown is InvoiceIssuedUiState.Ready) {
                    read.copy(pdfProblem = shown.pdfProblem, print = shown.print, busy = shown.busy)
                } else {
                    read
                }
            }
        }
    }

    /** Prints the ticket of the invoice on the associated printer (UC-G01). */
    fun print() {
        val shown = (mutableState.value as? InvoiceIssuedUiState.Ready)?.takeIf { it.print != PrintUiState.Printing }
            ?: return
        mutableState.value = shown.copy(print = PrintUiState.Printing)
        viewModelScope.launch {
            val result = printActions.print(id)
            (mutableState.value as? InvoiceIssuedUiState.Ready)?.let {
                mutableState.value = it.copy(print = PrintUiState.Done(result))
            }
        }
    }

    /** Opens the PDF of the invoice in another application (UC-G02). */
    fun openPdf() = pdf { pdfActions.open(id) }

    /** Offers to send the PDF of the invoice with another application (UC-G03). */
    fun sharePdf() = pdf { pdfActions.share(id) }

    private fun pdf(action: suspend () -> PdfOutcome) {
        val shown = (mutableState.value as? InvoiceIssuedUiState.Ready)?.takeIf { !it.busy } ?: return
        mutableState.value = shown.copy(busy = true, pdfProblem = null)
        viewModelScope.launch {
            val problem = action().takeIf { it != PdfOutcome.DONE }
            (mutableState.value as? InvoiceIssuedUiState.Ready)?.let {
                mutableState.value = it.copy(busy = false, pdfProblem = problem)
            }
        }
    }

    private companion object {
        /** Key under which the screen remembers that the ticket of this sale was printed by itself. */
        const val AUTO_PRINTED = "autoPrinted"
    }
}

/**
 * Confirmation of an issue wired to its view model.
 *
 * @param onNewSale called when the user starts another sale.
 * @param onHome called when the user goes back to the home screen.
 * @param viewModel holder of the invoice state.
 */
@Composable
fun InvoiceIssuedRoute(
    onNewSale: () -> Unit,
    onHome: () -> Unit,
    viewModel: InvoiceIssuedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InvoiceIssuedScreen(
        state = state,
        actions = InvoiceIssuedActions(
            onNewSale = onNewSale,
            onHome = onHome,
            onPrint = viewModel::print,
            onOpenPdf = viewModel::openPdf,
            onSharePdf = viewModel::sharePdf,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do once a sale is issued.
 *
 * @property onNewSale called when the user starts another sale.
 * @property onHome called when the user goes back to the home screen.
 * @property onPrint called when the user prints the ticket of the invoice.
 * @property onOpenPdf called when the user opens the PDF of the invoice.
 * @property onSharePdf called when the user shares the PDF of the invoice.
 * @property onRetry called when the user asks to read the invoice again.
 */
class InvoiceIssuedActions(
    val onNewSale: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onPrint: () -> Unit = {},
    val onOpenPdf: () -> Unit = {},
    val onSharePdf: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * What was just issued: the number, the identifier printed on every invoice, the customer, the
 * total and where the invoice stands with the OBR.
 *
 * @param state the invoice state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun InvoiceIssuedScreen(state: InvoiceIssuedUiState, actions: InvoiceIssuedActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.invoice_issued_title), modifier = modifier, onBack = actions.onHome) {
        when (state) {
            InvoiceIssuedUiState.Loading -> LoadingState()
            InvoiceIssuedUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoice_load_error),
                onRetry = actions.onRetry,
            )
            InvoiceIssuedUiState.NotFound -> ErrorState(message = stringResource(R.string.invoice_not_found))
            is InvoiceIssuedUiState.Ready -> FormColumn {
                val invoice = state.invoice.content
                Outcome(state.status, invoice.number, invoice.totals.total)
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        state.receipt?.let {
                            Fact(stringResource(R.string.invoice_registered_number), it.registeredNumber)
                            Fact(stringResource(R.string.invoice_registered_date), it.registeredDate)
                        }
                        Fact(stringResource(R.string.sale_customer), invoice.customer.name)
                        Fact(stringResource(R.string.sale_payment), invoice.paymentType.label())
                        Fact(stringResource(R.string.invoice_issued_date), dateTimeText(invoice.issuedAt))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.invoice_issued_identifier),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Printed on every invoice: shown in full, in fixed-width figures.
                        Text(text = invoice.identifier, fontFamily = FontFamily.Monospace)
                    }
                }
                PrintButton(actions.onPrint, state.print, enabled = !state.busy)
                PdfButtons(actions.onOpenPdf, actions.onSharePdf, state.pdfProblem, enabled = !state.busy)
                Button(onClick = actions.onNewSale, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invoice_issued_new_sale))
                }
                OutlinedButton(onClick = actions.onHome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invoice_issued_home))
                }
            }
        }
    }
}

/** What happened: the status with its colour and its icon, the number and the amount to collect. */
@Composable
private fun Outcome(status: InvoiceStatus, number: String, total: Money) {
    val tone = status.tone()
    val palette = tone.palette()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Box(
            modifier = Modifier.size(OutcomeIconSize).background(palette.container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(tone.icon(), contentDescription = null, Modifier.size(OutcomeIconSize / 2), palette.content)
        }
        Text(
            text = stringResource(R.string.invoice_issued_number, number),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        // A refusal carries the message of the OBR: it needs the width of a banner.
        if (status is InvoiceStatus.Rejected) StatusBanner(tone, status.label()) else StatusChip(tone, status.label())
        MoneyText(total, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(text = label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
