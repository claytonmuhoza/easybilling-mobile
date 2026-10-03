package com.claytonmuhoza.easybilling.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.PricedSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the cash register does, grouped so the view model receives it as one.
 *
 * @property edit changes the sale being typed.
 * @property price computes the sale.
 * @property issue issues the sale as an invoice.
 * @property readiness tells whether invoices can be issued.
 */
class SaleUseCases @Inject constructor(
    val edit: EditSale,
    val price: PriceSale,
    val issue: IssueSale,
    val readiness: CheckBillingReadiness,
)

/** The two parts of the cash register; a narrow screen shows one at a time. */
enum class SalePane {
    /** The items to add. */
    CATALOGUE,

    /** The sale: its lines, its customer, its payment and its total. */
    CART,
}

/** Why the numbering refuses a new invoice. */
enum class IssueRefusal {
    /** The counter reached its last number. */
    NUMBERS_EXHAUSTED,

    /** The number does not fit in the identifier of the OBR. */
    IDENTIFIER_TOO_LONG,
}

/**
 * The line being modified.
 *
 * @property itemId the item of the line.
 * @property form the quantity, the discount and the tax amounts as typed.
 * @property problems the rules broken at the last attempt.
 */
data class LineEditor(
    val itemId: ItemId,
    val form: SaleLineForm,
    val problems: Set<SaleLineProblem> = emptySet(),
)

/** State of the cash register. */
sealed interface SaleUiState {
    /** The sale is being read. */
    data object Loading : SaleUiState

    /** The sale could not be read. */
    data object Failed : SaleUiState

    /** The configuration is not complete: nothing can be sold yet. */
    data class NotReady(val readiness: BillingReadiness) : SaleUiState

    /** The sale was issued as the invoice [invoiceId]: the screen shows it. */
    data class Issued(val invoiceId: Long) : SaleUiState

    /**
     * The sale is shown.
     *
     * @property sale the sale being typed, with its amounts.
     * @property query what the user typed in the search field.
     * @property results the items found, [SaleViewModel.LIMIT] at most.
     * @property heldCount how many sales are on hold.
     * @property pane the part a narrow screen shows.
     * @property editor the line being modified, or null.
     * @property refusal why the numbering refused the last attempt, or null.
     * @property busy true while a change is written.
     * @property failed true when the last change could not be written.
     */
    data class Ready(
        val sale: PricedSale,
        val query: String = "",
        val results: List<Item> = emptyList(),
        val heldCount: Int = 0,
        val pane: SalePane = SalePane.CATALOGUE,
        val editor: LineEditor? = null,
        val refusal: IssueRefusal? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : SaleUiState
}

/**
 * Drives the cash register (UC-C01 steps 1 to 5, UC-C02, UC-C03). The catalogue and the line being
 * modified have their own part, [catalogue] and [lines]; all share one state.
 */
@HiltViewModel
class SaleViewModel @Inject constructor(
    useCases: SaleUseCases,
    items: ItemRepository,
    drafts: SaleDraftRepository,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val holder = SaleStateHolder(viewModelScope, useCases, items, drafts)

    /** The search and the items added with one touch. */
    val catalogue = SaleCatalogue(holder)

    /** The line being modified. */
    val lines = SaleLineEditing(holder)

    /** The state to show. */
    val state: StateFlow<SaleUiState> = holder.state.asStateFlow()

    /** Reads the sale being typed. Called each time the screen is shown, and to retry. */
    fun load() {
        val shown = holder.ready
        viewModelScope.launch {
            holder.state.value = storageCall {
                val readiness = holder.useCases.readiness()
                when {
                    !readiness.ready -> SaleUiState.NotReady(readiness)
                    shown == null -> holder.started()
                    else -> holder.refreshed(shown)
                }
            }.getOrElse { SaleUiState.Failed }
        }
    }

    /** @param paymentType how the sale is paid. */
    fun setPaymentType(paymentType: PaymentType) = holder.change { shown ->
        holder.useCases.edit.setPaymentType(paymentType)
        shown
    }

    /** @param pane the part a narrow screen shows. */
    fun showPane(pane: SalePane) = holder.show { it.copy(pane = pane) }

    /** Puts the sale on hold and starts an empty one (UC-C02). */
    fun hold() = holder.change { shown ->
        holder.useCases.edit.hold()
        shown.copy(pane = SalePane.CATALOGUE, editor = null)
    }

    /** Issues the sale as an invoice (UC-C01 steps 4 and 5), or shows why it cannot be. */
    fun issue() {
        val shown = holder.ready?.takeIf { !it.busy } ?: return
        val actor = sessions.current.value?.userId ?: return
        val idle = shown.copy(failed = false, refusal = null)
        holder.state.value = idle.copy(busy = true)
        viewModelScope.launch {
            holder.state.value = storageCall {
                when (val result = holder.useCases.issue(shown.sale.draft.id, actor)) {
                    // The invoice is stored: it is sent without making the cashier wait.
                    is IssueSale.Result.Issued ->
                        SaleUiState.Issued(result.invoice.id.value).also { transmission.kick() }
                    is IssueSale.Result.NotReady -> SaleUiState.NotReady(result.readiness)
                    is IssueSale.Result.Invalid -> idle.copy(sale = result.sale)
                    IssueSale.Result.NumbersExhausted -> idle.copy(refusal = IssueRefusal.NUMBERS_EXHAUSTED)
                    IssueSale.Result.IdentifierTooLong -> idle.copy(refusal = IssueRefusal.IDENTIFIER_TOO_LONG)
                    IssueSale.Result.NotFound -> holder.refreshed(idle)
                }
            }.getOrElse { idle.copy(failed = true) }
        }
    }

    /** Limits of the cash register. */
    companion object {
        /** Most items listed at once: beyond, the user refines the search. */
        const val LIMIT = 50
    }
}
