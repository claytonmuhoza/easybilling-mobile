package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.stock.DmcLineProgress
import com.claytonmuhoza.easybilling.domain.stock.EnterImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.ImportForm
import com.claytonmuhoza.easybilling.domain.stock.ImportProblem
import com.claytonmuhoza.easybilling.domain.stock.ImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.ReadDmc
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.navigation.ImportEntryDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * What the entry of imported goods uses, grouped so the view model receives it as one.
 *
 * @property items the catalogue.
 * @property read reads the declaration and what was entered of it.
 * @property enter enters the goods into the stock.
 */
class ImportEntryUseCases @Inject constructor(
    val items: ItemRepository,
    val read: ReadDmc,
    val enter: EnterImportedGoods,
)

/** State of the entry of imported goods. */
sealed interface ImportEntryUiState {
    /** The line of the declaration is being read. */
    data object Loading : ImportEntryUiState

    /** The line could not be read. */
    data object Failed : ImportEntryUiState

    /** The declaration is not on this device, or no longer has the line. */
    data object LineNotFound : ImportEntryUiState

    /**
     * The entry can be typed.
     *
     * @property reference the reference of the declaration.
     * @property line the line the goods come from, with what was entered of it.
     * @property item the item of the catalogue chosen, or null.
     * @property query what the user typed to find the item.
     * @property matches the items kept in stock that match [query].
     * @property quantity the quantity typed.
     * @property unitCost the unit purchase price typed.
     * @property piecesPerPacket the number of pieces per packet typed.
     * @property packaging the kind of packaging typed.
     * @property description the description typed.
     * @property problems the rules broken at the last attempt.
     * @property exceeds what is left to enter of the line when the last attempt asked for more, or null.
     * @property refused why the item chosen cannot move, or null.
     * @property busy true while the entry is written.
     * @property failed true when the last attempt could not be written.
     */
    data class Form(
        val reference: String,
        val line: DmcLineProgress,
        val item: Item? = null,
        val query: String = "",
        val matches: List<Item> = emptyList(),
        val quantity: String = "",
        val unitCost: String = "",
        val piecesPerPacket: String = "",
        val packaging: String = "",
        val description: String = "",
        val problems: Set<ImportProblem> = emptySet(),
        val exceeds: BigDecimal? = null,
        val refused: StockProblem? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : ImportEntryUiState {
        /** @return what was typed, for the use case. */
        fun typed(): ImportForm = ImportForm(
            dmcReference = reference,
            dmcLine = line.line.rank,
            itemId = item?.id,
            quantity = quantity,
            unitCost = unitCost,
            piecesPerPacket = piecesPerPacket,
            packaging = packaging,
            description = description,
        )
    }

    /** The entry is recorded. */
    data object Done : ImportEntryUiState
}

/**
 * Drives the entry into the stock of a line of a customs declaration (UC-F04): the item of the
 * catalogue it is, its quantity, its purchase price and its packaging.
 */
@HiltViewModel
class ImportEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val useCases: ImportEntryUseCases,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<ImportEntryDestination>()

    private val mutableState = MutableStateFlow<ImportEntryUiState>(ImportEntryUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ImportEntryUiState> = mutableState.asStateFlow()

    /**
     * Reads the line, or reads the items again when the entry is already being typed: an item may
     * have been created on another screen.
     */
    fun load() {
        viewModelScope.launch {
            val typing = mutableState.value as? ImportEntryUiState.Form
            mutableState.value = storageCall {
                if (typing != null) typing.copy(matches = matching(typing.query)) else opened()
            }.getOrElse { ImportEntryUiState.Failed }
        }
    }

    private suspend fun opened(): ImportEntryUiState {
        val line = useCases.read(destination.reference)?.lines?.firstOrNull { it.line.rank == destination.line }
            ?: return ImportEntryUiState.LineNotFound
        return ImportEntryUiState.Form(
            reference = destination.reference,
            line = line,
            // The item the line was last entered as, unless it can no longer move.
            item = line.item?.takeIf { it.details.stockTracked && !it.archived },
            matches = matching(""),
            packaging = line.line.packaging.take(ImportedGoods.MAX_LENGTH),
        )
    }

    /** @param query the new content of the search of the item. */
    fun onQueryChange(query: String) {
        update { copy(query = query) }
        viewModelScope.launch {
            val matches = storageCall { matching(query) }.getOrElse { emptyList() }
            update { if (this.query == query) copy(matches = matches) else this }
        }
    }

    /**
     * @param change what the user changed of the entry being typed. The refusal of the last attempt
     * is forgotten: it was about what was typed then.
     */
    fun onFormChange(change: ImportEntryUiState.Form.() -> ImportEntryUiState.Form) =
        update { change().copy(exceeds = null, refused = null, failed = false) }

    /** Enters the goods into the stock. */
    fun submit() {
        val form = (mutableState.value as? ImportEntryUiState.Form)?.takeUnless { it.busy } ?: return
        val actor = sessions.current.value?.userId ?: return
        mutableState.value = form.copy(busy = true, failed = false)
        viewModelScope.launch {
            val idle = form.copy(busy = false)
            mutableState.value = storageCall {
                when (val result = useCases.enter(form.typed(), actor)) {
                    is EnterImportedGoods.Result.Recorded -> ImportEntryUiState.Done.also { transmission.kick() }
                    is EnterImportedGoods.Result.Invalid -> idle.copy(problems = result.problems)
                    is EnterImportedGoods.Result.Refused -> idle.copy(refused = result.problem)
                    is EnterImportedGoods.Result.ExceedsDmc -> idle.copy(exceeds = result.remaining)
                    EnterImportedGoods.Result.LineNotFound -> ImportEntryUiState.LineNotFound
                }
            }.getOrElse { idle.copy(failed = true) }
        }
    }

    /** @return the active items kept in stock that match [query]. */
    private suspend fun matching(query: String): List<Item> =
        useCases.items.search(query, archived = false, limit = SEARCHED)
            .filter { it.details.stockTracked }
            .take(SHOWN)

    private fun update(change: ImportEntryUiState.Form.() -> ImportEntryUiState.Form) {
        val form = mutableState.value as? ImportEntryUiState.Form ?: return
        mutableState.value = form.change()
    }

    /** Limits of the choice of the item. */
    companion object {
        /** Most items read for a search, before the ones that are not kept in stock are left out. */
        const val SEARCHED = 100

        /** Most items offered at once: beyond, the user refines the search. */
        const val SHOWN = 5
    }
}
