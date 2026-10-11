package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.SetReorderThreshold
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
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
     * @property threshold its reorder threshold, or null when it has none.
     * @property thresholdText the threshold as typed.
     * @property thresholdInvalid whether what was typed for the threshold was refused.
     */
    data class Ready(
        val item: Item,
        val quantity: BigDecimal,
        val movements: List<MovementRow>,
        val threshold: BigDecimal? = null,
        val thresholdText: String = threshold?.toPlainString().orEmpty(),
        val thresholdInvalid: Boolean = false,
    ) : StockItemUiState {
        /** Whether the stock is at the reorder threshold or under it. */
        val toReorder: Boolean get() = threshold != null && quantity <= threshold
    }
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
    private val settings: StockSettingsRepository,
    private val setThreshold: SetReorderThreshold,
    private val sessions: SessionManager,
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
                        threshold = settings.thresholds()[id],
                    )
                }
            }.getOrElse { StockItemUiState.Failed }
        }
    }

    /** @param text the new content of the threshold field. */
    fun onThresholdChange(text: String) {
        val ready = mutableState.value as? StockItemUiState.Ready ?: return
        mutableState.value = ready.copy(thresholdText = text, thresholdInvalid = false)
    }

    /** Keeps the threshold typed; an empty one takes the threshold away. */
    fun saveThreshold() {
        val ready = mutableState.value as? StockItemUiState.Ready ?: return
        val actor = sessions.current.value?.userId ?: return
        viewModelScope.launch {
            val result = storageCall { setThreshold(id, ready.thresholdText, actor) }.getOrNull()
            mutableState.value = if (result is SetReorderThreshold.Result.Saved) {
                ready.copy(
                    threshold = result.threshold,
                    thresholdText = result.threshold?.toPlainString().orEmpty(),
                    thresholdInvalid = false,
                )
            } else {
                ready.copy(thresholdInvalid = true)
            }
        }
    }

    /** Limits of the history. */
    companion object {
        /** Most movements shown: the export of the whole history comes with the reports. */
        const val LIMIT = 50
    }
}
