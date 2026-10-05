package com.claytonmuhoza.easybilling.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceDay
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceFilter
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoicePeriod
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.groupedByDay
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** State of the list of the invoices. */
sealed interface InvoiceListUiState {
    /** The invoices are being read. */
    data object Loading : InvoiceListUiState

    /** The invoices could not be read. */
    data object Failed : InvoiceListUiState

    /**
     * The invoices are shown.
     *
     * @property query what the user typed in the search field.
     * @property status the status the list is filtered by, or null for every status.
     * @property period the period the list is filtered by.
     * @property days the invoices found, grouped by day, the most recent first.
     * @property counts how many invoices match the search and the period, for each status.
     * @property today the current day in Bujumbura, which names the first days.
     * @property anyInvoice whether an invoice was ever issued, whatever the filters.
     * @property limited whether more invoices match than the [InvoiceListViewModel.LIMIT] shown.
     */
    data class Ready(
        val query: String = "",
        val status: InvoiceStatusKind? = null,
        val period: InvoicePeriod = InvoicePeriod.ALL,
        val days: List<InvoiceDay> = emptyList(),
        val counts: Map<InvoiceStatusKind, Int> = emptyMap(),
        val today: LocalDate,
        val anyInvoice: Boolean = true,
        val limited: Boolean = false,
    ) : InvoiceListUiState
}

/** Lists, searches and filters the issued invoices (UC-C08). */
@HiltViewModel
class InvoiceListViewModel @Inject constructor(
    private val listing: InvoiceListing,
    private val invoices: InvoiceRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private var query = ""
    private var status: InvoiceStatusKind? = null
    private var period = InvoicePeriod.ALL
    private var search: Job? = null

    private val mutableState = MutableStateFlow<InvoiceListUiState>(InvoiceListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<InvoiceListUiState> = mutableState.asStateFlow()

    /** Reads the invoices. Called each time the screen is shown, and to retry. */
    fun load() = refresh()

    /** @param query the new content of the search field; the list follows at once. */
    fun onQueryChange(query: String) {
        this.query = query
        // The field shows what is typed without waiting for the database.
        (mutableState.value as? InvoiceListUiState.Ready)?.let { mutableState.value = it.copy(query = query) }
        refresh()
    }

    /** @param status the status to list, or null for every status. */
    fun onStatusChange(status: InvoiceStatusKind?) {
        this.status = status
        refresh()
    }

    /** @param period the period to list. */
    fun onPeriodChange(period: InvoicePeriod) {
        this.period = period
        refresh()
    }

    private fun refresh() {
        search?.cancel()
        search = viewModelScope.launch {
            val asked = query
            val now = time.now()
            val filter = InvoiceFilter(asked, status, period.since(now))
            val result = storageCall {
                val found = listing.search(filter, LIMIT)
                InvoiceListUiState.Ready(
                    query = asked,
                    status = filter.status,
                    period = period,
                    days = found.groupedByDay(),
                    counts = listing.countByStatus(filter),
                    today = BusinessCalendar.today(time),
                    anyInvoice = found.isNotEmpty() || invoices.count() > 0,
                    limited = found.size == LIMIT,
                )
            }.getOrElse { InvoiceListUiState.Failed }
            // A newer search may have been typed while this one ran: its text stays in the field.
            mutableState.value = if (result is InvoiceListUiState.Ready) result.copy(query = query) else result
        }
    }

    /** Limits of the list. */
    companion object {
        /** Most invoices listed at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}
