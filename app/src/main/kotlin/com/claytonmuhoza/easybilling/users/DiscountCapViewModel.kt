package com.claytonmuhoza.easybilling.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettingsRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaveDiscountSettings
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** State of the cap of the discount of the cashier. */
sealed interface DiscountCapUiState {
    /** The cap is being read. */
    data object Loading : DiscountCapUiState

    /** The cap could not be read. */
    data object Failed : DiscountCapUiState

    /**
     * The cap can be changed.
     *
     * @property limited whether the discount of the cashier is limited.
     * @property value the cap typed, in percent.
     * @property outOfRange true when the cap typed was refused.
     * @property saved true once the cap shown is saved.
     * @property busy true while the cap is being saved.
     * @property failed true when the last save did not succeed.
     */
    data class Editing(
        val limited: Boolean = false,
        val value: String = "",
        val outOfRange: Boolean = false,
        val saved: Boolean = false,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : DiscountCapUiState
}

/** Drives the cap of the discount a cashier gives alone (UC-C03, D50). */
@HiltViewModel
class DiscountCapViewModel @Inject constructor(
    private val settings: DiscountSettingsRepository,
    private val save: SaveDiscountSettings,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<DiscountCapUiState>(DiscountCapUiState.Loading)

    /** The state to show. */
    val state: StateFlow<DiscountCapUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the cap. Also used to retry. */
    fun load() {
        mutableState.value = DiscountCapUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall { settings.read().cashierCap }.fold(
                onSuccess = { cap ->
                    val typed = cap?.stripTrailingZeros()?.toPlainString().orEmpty()
                    DiscountCapUiState.Editing(limited = cap != null, value = typed)
                },
                onFailure = { DiscountCapUiState.Failed },
            )
        }
    }

    /** @param limited whether the discount of the cashier is limited. */
    fun onLimitedChange(limited: Boolean) = update { copy(limited = limited, outOfRange = false, saved = false) }

    /** @param value the new content of the cap field. */
    fun onValueChange(value: String) = update { copy(value = value, outOfRange = false, saved = false) }

    /** Saves the cap, or shows that it is out of range. */
    fun submit() {
        val editing = (mutableState.value as? DiscountCapUiState.Editing)?.takeUnless { it.busy }
        val actor = sessions.current.value?.userId
        if (editing == null || actor == null) return
        val cap = if (editing.limited) editing.value.trim().replace(',', '.').toBigDecimalOrNull() else null
        if (editing.limited && cap == null) {
            mutableState.value = editing.copy(outOfRange = true)
        } else {
            saveCap(editing, cap, actor)
        }
    }

    private fun saveCap(editing: DiscountCapUiState.Editing, cap: BigDecimal?, actor: UserId) {
        mutableState.value = editing.copy(busy = true, failed = false)
        viewModelScope.launch {
            mutableState.value = storageCall { save(cap, actor) }.fold(
                onSuccess = { result ->
                    when (result) {
                        SaveDiscountSettings.Result.Saved -> editing.copy(saved = true)
                        SaveDiscountSettings.Result.OutOfRange -> editing.copy(outOfRange = true)
                    }
                },
                onFailure = { editing.copy(failed = true) },
            )
        }
    }

    private fun update(change: DiscountCapUiState.Editing.() -> DiscountCapUiState.Editing) {
        val editing = mutableState.value as? DiscountCapUiState.Editing ?: return
        mutableState.value = editing.change()
    }
}
