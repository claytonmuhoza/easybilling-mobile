package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.navigation.StockItemDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * A movement of the history of an item.
 *
 * @property movement the movement.
 * @property status where it stands with the OBR.
 */
data class MovementRow(val movement: StockMovement, val status: MovementStatus)

/** State of the stock of an item. */
sealed interface StockItemUiState {
    /** The stock is being read. */
    data object Loading : StockItemUiState

    /** The stock could not be read. */
    data object Failed : StockItemUiState

    /** The item has no stock to show, for [problem]. */
    data class Unavailable(val problem: StockProblem) : StockItemUiState

    /**
     * The stock is shown.
     *
     * @property item the item.
     * @property quantity its stock: the sum of its movements.
     * @property movements its last movements, the most recent first, [StockItemViewModel.LIMIT] at most.
     */
    data class Ready(val item: Item, val quantity: BigDecimal, val movements: List<MovementRow>) : StockItemUiState
}

/**
 * Shows the stock of an item and the history of its movements (UC-F10). The status of a movement
 * is read again each time a pass over the send queue ends.
 */
@HiltViewModel
class StockItemViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    private val stock: StockRepository,
    transmission: TransmissionRunner,
) : ViewModel() {

    private val id = ItemId(savedStateHandle.toRoute<StockItemDestination>().id)

    private val mutableState = MutableStateFlow<StockItemUiState>(StockItemUiState.Loading)

    /** The state to show. */
    val state: StateFlow<StockItemUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { transmission.passes.drop(1).collect { load() } }
    }

    /** Reads the stock. Called each time the screen is shown, and to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall {
                val item = items.find(id)
                when {
                    item == null -> StockItemUiState.Unavailable(StockProblem.ITEM_NOT_FOUND)
                    !item.details.stockTracked -> StockItemUiState.Unavailable(StockProblem.ITEM_NOT_TRACKED)
                    else -> StockItemUiState.Ready(
                        item = item,
                        quantity = stock.quantityOf(id),
                        movements = stock.movementsOf(id, LIMIT).mapNotNull { movement ->
                            stock.statusOf(movement.id)?.let { MovementRow(movement, it) }
                        },
                    )
                }
            }.getOrElse { StockItemUiState.Failed }
        }
    }

    /** Limits of the history. */
    companion object {
        /** Most movements shown: the export of the whole history comes with the reports. */
        const val LIMIT = 50
    }
}
