package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.stock.ReadStock
import com.claytonmuhoza.easybilling.domain.stock.StockLine
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the list of the items kept in stock. */
sealed interface StockListUiState {
    /** The stock is being read. */
    data object Loading : StockListUiState

    /** The stock could not be read. */
    data object Failed : StockListUiState

    /**
     * The stock is shown.
     *
     * @property query what the user typed in the search field.
     * @property lines the items kept in stock that match, with their stock.
     */
    data class Ready(val query: String, val lines: List<StockLine>) : StockListUiState
}

/** Lists the items kept in stock with their stock, and searches them (UC-F10). */
@HiltViewModel
class StockListViewModel @Inject constructor(private val readStock: ReadStock) : ViewModel() {

    private var query = ""
    private var search: Job? = null

    private val mutableState = MutableStateFlow<StockListUiState>(StockListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<StockListUiState> = mutableState.asStateFlow()

    /** Reads the stock. Called each time the screen is shown, and to retry. */
    fun load() = refresh()

    /** @param query the new content of the search field; the list follows at once. */
    fun onQueryChange(query: String) {
        this.query = query
        // The field shows what is typed without waiting for the database.
        (mutableState.value as? StockListUiState.Ready)?.let { mutableState.value = it.copy(query = query) }
        refresh()
    }

    private fun refresh() {
        search?.cancel()
        search = viewModelScope.launch {
            val asked = query
            val result = storageCall { StockListUiState.Ready(asked, readStock(asked, LIMIT)) }
                .getOrElse { StockListUiState.Failed }
            // A newer search may have been typed while this one ran: its text stays in the field.
            mutableState.value = if (result is StockListUiState.Ready) result.copy(query = query) else result
        }
    }

    /** Limits of the list. */
    companion object {
        /** Most items of the catalogue looked through at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}
