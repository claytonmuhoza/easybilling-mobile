package com.claytonmuhoza.easybilling.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.catalog.ReadItemRules
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the list of the items. */
sealed interface ItemListUiState {
    /** The items are being read. */
    data object Loading : ItemListUiState

    /** The items could not be read. */
    data object Failed : ItemListUiState

    /**
     * The items are shown.
     *
     * @property query what the user typed in the search field.
     * @property showArchived whether the archived items are listed instead of the active ones.
     * @property items the items found, [ItemListViewModel.LIMIT] at most.
     * @property activeCount how many active items there are, whatever the search.
     * @property archivedCount how many archived items there are, whatever the search.
     * @property registrations the taxes the business is registered for: they tell which items
     * still miss their VAT.
     */
    data class Ready(
        val query: String,
        val showArchived: Boolean,
        val items: List<Item>,
        val activeCount: Int,
        val archivedCount: Int,
        val registrations: TaxRegistrations,
    ) : ItemListUiState
}

/** Lists and searches the items of the catalogue (UC-B07). */
@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val items: ItemRepository,
    private val readRules: ReadItemRules,
) : ViewModel() {

    private var query = ""
    private var showArchived = false
    private var search: Job? = null

    private val mutableState = MutableStateFlow<ItemListUiState>(ItemListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ItemListUiState> = mutableState.asStateFlow()

    /** Reads the items. Called each time the screen is shown, and to retry. */
    fun load() = refresh()

    /** @param query the new content of the search field; the list follows at once. */
    fun onQueryChange(query: String) {
        this.query = query
        // The field shows what is typed without waiting for the database.
        (mutableState.value as? ItemListUiState.Ready)?.let { mutableState.value = it.copy(query = query) }
        refresh()
    }

    /** @param showArchived whether to list the archived items instead of the active ones. */
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
                ItemListUiState.Ready(
                    query = asked,
                    showArchived = archived,
                    items = items.search(asked, archived, LIMIT),
                    activeCount = items.count(archived = false),
                    archivedCount = items.count(archived = true),
                    registrations = readRules().registrations,
                )
            }.getOrElse { ItemListUiState.Failed }
            // A newer search may have been typed while this one ran: its text stays in the field.
            mutableState.value = if (result is ItemListUiState.Ready) result.copy(query = query) else result
        }
    }

    /** Limits of the list. */
    companion object {
        /** Most items listed at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}
