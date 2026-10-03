package com.claytonmuhoza.easybilling.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the list of the customers. */
sealed interface CustomerListUiState {
    /** The customers are being read. */
    data object Loading : CustomerListUiState

    /** The customers could not be read. */
    data object Failed : CustomerListUiState

    /**
     * The customers are shown.
     *
     * @property query what the user typed in the search field.
     * @property showArchived whether the archived customers are listed instead of the active ones.
     * @property counter the counter customer, shown apart.
     * @property customers the customers found, [CustomerListViewModel.LIMIT] at most.
     * @property activeCount how many active customers there are, whatever the search.
     * @property archivedCount how many archived customers there are, whatever the search.
     */
    data class Ready(
        val query: String,
        val showArchived: Boolean,
        val counter: Customer,
        val customers: List<Customer>,
        val activeCount: Int,
        val archivedCount: Int,
    ) : CustomerListUiState
}

/** Lists and searches the customers (UC-B07). */
@HiltViewModel
class CustomerListViewModel @Inject constructor(private val customers: CustomerRepository) : ViewModel() {

    private var query = ""
    private var showArchived = false
    private var search: Job? = null

    private val mutableState = MutableStateFlow<CustomerListUiState>(CustomerListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<CustomerListUiState> = mutableState.asStateFlow()

    /** Reads the customers. Called each time the screen is shown, and to retry. */
    fun load() = refresh()

    /** @param query the new content of the search field; the list follows at once. */
    fun onQueryChange(query: String) {
        this.query = query
        // The field shows what is typed without waiting for the database.
        (mutableState.value as? CustomerListUiState.Ready)?.let { mutableState.value = it.copy(query = query) }
        refresh()
    }

    /** @param showArchived whether to list the archived customers instead of the active ones. */
    fun onShowArchivedChange(showArchived: Boolean) {
        this.showArchived = showArchived
        refresh()
    }

    private fun refresh() {
        search?.cancel()
        search = viewModelScope.launch {
            val asked = query
            val archived = showArchived
            val result = storageCall {
                CustomerListUiState.Ready(
                    query = asked,
                    showArchived = archived,
                    counter = customers.counter(),
                    customers = customers.search(asked, archived, LIMIT),
                    activeCount = customers.count(archived = false),
                    archivedCount = customers.count(archived = true),
                )
            }.getOrElse { CustomerListUiState.Failed }
            // A newer search may have been typed while this one ran: its text stays in the field.
            mutableState.value = if (result is CustomerListUiState.Ready) result.copy(query = query) else result
        }
    }

    /** Limits of the list. */
    companion object {
        /** Most customers listed at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}
