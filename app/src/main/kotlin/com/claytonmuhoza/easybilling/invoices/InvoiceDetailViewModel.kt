package com.claytonmuhoza.easybilling.invoices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.documents.InvoicePdfActions
import com.claytonmuhoza.easybilling.documents.PdfOutcome
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.RedoSale
import com.claytonmuhoza.easybilling.domain.invoicing.ResendRejectedInvoice
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.domain.transmission.VerifyInvoice
import com.claytonmuhoza.easybilling.navigation.InvoiceDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What can be done with an invoice, grouped so the view model receives it as one.
 *
 * @property verify asks the OBR for the invoice.
 * @property resend puts a refused invoice back in the send queue.
 * @property redo starts the sale of an invoice over.
 * @property pdf opens or shares the PDF of an invoice.
 */
class InvoiceUseCases @Inject constructor(
    val verify: VerifyInvoice,
    val resend: ResendRejectedInvoice,
    val redo: RedoSale,
    val pdf: InvoicePdfActions,
)

/** Where a check at the OBR stands. */
sealed interface VerificationUiState {
    /** The OBR is being asked. */
    data object Running : VerificationUiState

    /** The OBR answered, or could not: [result] says which. */
    data class Done(val result: VerifyInvoice.Result) : VerificationUiState
}

/** What the last action on a refused invoice gave. */
sealed interface InvoiceNotice {
    /** The invoice is back in the send queue. */
    data object Resent : InvoiceNotice

    /** The identifier would be too long with the NIF of the tax profile: nothing was sent again. */
    data object IdentifierTooLong : InvoiceNotice

    /** The action could not be written. */
    data object Failed : InvoiceNotice

    /** The sale was started over, without [skippedLines] lines whose item is archived. */
    data class SaleStarted(val skippedLines: Int) : InvoiceNotice
}

/** State of the detail of an invoice. */
sealed interface InvoiceDetailUiState {
    /** The invoice is being read. */
    data object Loading : InvoiceDetailUiState

    /** The invoice could not be read. */
    data object Failed : InvoiceDetailUiState

    /** There is no invoice with that identifier. */
    data object NotFound : InvoiceDetailUiState

    /**
     * The invoice is shown.
     *
     * @property invoice the invoice.
     * @property status where it stands with the OBR.
     * @property receipt the acknowledgement of the OBR, or null while there is none.
     * @property verification the check at the OBR, or null before the first one.
     * @property notice what the last action gave, or null.
     * @property pdfProblem why the PDF was not opened or shared at the last try, or null.
     * @property busy true while an action is written or a PDF created.
     */
    data class Ready(
        val invoice: Invoice,
        val status: InvoiceStatus,
        val receipt: ObrReceipt? = null,
        val verification: VerificationUiState? = null,
        val notice: InvoiceNotice? = null,
        val pdfProblem: PdfOutcome? = null,
        val busy: Boolean = false,
    ) : InvoiceDetailUiState
}

/**
 * Shows an invoice, checks it at the OBR on request (UC-C09) and deals with a refusal (UC-D04).
 * The status is read again each time a pass over the send queue ends.
 */
@HiltViewModel
class InvoiceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val invoices: InvoiceRepository,
    private val receipts: ReceiptArchive,
    private val useCases: InvoiceUseCases,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val id = InvoiceId(savedStateHandle.toRoute<InvoiceDestination>().id)

    private val mutableState = MutableStateFlow<InvoiceDetailUiState>(InvoiceDetailUiState.Loading)

    /** The state to show. */
    val state: StateFlow<InvoiceDetailUiState> = mutableState.asStateFlow()

    private val ready get() = mutableState.value as? InvoiceDetailUiState.Ready

    init {
        viewModelScope.launch { transmission.passes.collect { load() } }
    }

    /** Reads the invoice. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val read = storageCall { read() }.getOrElse { InvoiceDetailUiState.Failed }
            // What the user asked for on this screen stays shown when the status is read again.
            val shown = ready
            mutableState.value = if (read is InvoiceDetailUiState.Ready && shown != null) {
                read.copy(
                    verification = shown.verification,
                    notice = shown.notice,
                    pdfProblem = shown.pdfProblem,
                    busy = shown.busy,
                )
            } else {
                read
            }
        }
    }

    private suspend fun read(): InvoiceDetailUiState {
        val invoice = invoices.find(id)
        val status = invoices.statusOf(id)
        return if (invoice != null && status != null) {
            InvoiceDetailUiState.Ready(invoice, status, receipts.find(id))
        } else {
            InvoiceDetailUiState.NotFound
        }
    }

    /** Asks the OBR for the invoice (UC-C09). Nothing changes on the device, whatever it answers. */
    fun verify() {
        val shown = ready?.takeIf { it.verification != VerificationUiState.Running } ?: return
        mutableState.value = shown.copy(verification = VerificationUiState.Running)
        viewModelScope.launch {
            val result = storageCall { useCases.verify(id) }
                .getOrElse { VerifyInvoice.Result.Unavailable(it.javaClass.simpleName) }
            ready?.let { mutableState.value = it.copy(verification = VerificationUiState.Done(result)) }
        }
    }

    /** Puts the refused invoice back in the send queue, with the tax profile in force (UC-D04). */
    fun resend() = act { shown ->
        val actor = sessions.current.value?.userId ?: return@act shown
        when (val result = useCases.resend(id, actor)) {
            // The invoice is sent without making the user wait.
            is ResendRejectedInvoice.Result.Resent -> shown
                .copy(invoice = result.invoice, status = InvoiceStatus.Pending, notice = InvoiceNotice.Resent)
                .also { transmission.kick() }
            ResendRejectedInvoice.Result.IdentifierTooLong -> shown.copy(notice = InvoiceNotice.IdentifierTooLong)
            ResendRejectedInvoice.Result.NotRejected, ResendRejectedInvoice.Result.NotFound -> shown
        }
    }

    /** Starts the sale of the invoice over: the new sale becomes the one being typed (UC-D04). */
    fun redo() = act { shown ->
        when (val result = useCases.redo(id)) {
            is RedoSale.Result.Started -> shown.copy(notice = InvoiceNotice.SaleStarted(result.skippedLines))
            RedoSale.Result.NotFound -> shown
        }
    }

    /** Opens the PDF of the invoice in another application (UC-G02). */
    fun openPdf() = pdf { useCases.pdf.open(id) }

    /** Offers to send the PDF of the invoice with another application (UC-G03). */
    fun sharePdf() = pdf { useCases.pdf.share(id) }

    private fun pdf(action: suspend () -> PdfOutcome) {
        val shown = ready?.takeIf { !it.busy } ?: return
        mutableState.value = shown.copy(busy = true, pdfProblem = null)
        viewModelScope.launch {
            val problem = action().takeIf { it != PdfOutcome.DONE }
            ready?.let { mutableState.value = it.copy(busy = false, pdfProblem = problem) }
        }
    }

    /** Forgets the notice of the last action: the screen has shown it or acted on it. */
    fun dismissNotice() {
        ready?.let { mutableState.value = it.copy(notice = null) }
    }

    /** Runs one action at a time, and says so when it cannot be written. */
    private fun act(action: suspend (InvoiceDetailUiState.Ready) -> InvoiceDetailUiState.Ready) {
        val shown = ready?.takeIf { !it.busy } ?: return
        mutableState.value = shown.copy(busy = true, notice = null)
        viewModelScope.launch {
            val done = storageCall { action(shown) }.getOrElse { shown.copy(notice = InvoiceNotice.Failed) }
            // The check at the OBR may have ended meanwhile: it is kept.
            mutableState.value = done.copy(verification = ready?.verification ?: done.verification, busy = false)
        }
    }
}
