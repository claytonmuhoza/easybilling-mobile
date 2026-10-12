package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcProgress
import com.claytonmuhoza.easybilling.domain.stock.DmcRepository
import com.claytonmuhoza.easybilling.domain.stock.FetchDmc
import com.claytonmuhoza.easybilling.domain.stock.ReadDmc
import com.claytonmuhoza.easybilling.navigation.DmcDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a customs declaration was not read at the last attempt. */
sealed interface ImportsError {
    /** The reference is empty or too long. */
    data object InvalidReference : ImportsError

    /** The OBR refused, with its [message]. */
    data class Refused(val message: String) : ImportsError

    /** The OBR gave no line for the declaration. */
    data object Empty : ImportsError

    /** No usable answer came from the OBR. */
    data object Unavailable : ImportsError

    /** The tax profile or the connection to the EBMS is not configured. */
    data object NotConfigured : ImportsError

    /** The declaration could not be kept on the device. */
    data object Failed : ImportsError
}

/** State of the list of the customs declarations. */
sealed interface ImportsUiState {
    /** The declarations are being read. */
    data object Loading : ImportsUiState

    /** The declarations could not be read. */
    data object Failed : ImportsUiState

    /**
     * The declarations are shown.
     *
     * @property reference the reference being typed.
     * @property declarations the declarations already read, the most recently read first.
     * @property busy true while the OBR is asked.
     * @property error why the last reading gave nothing, or null.
     * @property fetched the reference of the declaration just read, to open once, or null.
     */
    data class Ready(
        val reference: String = "",
        val declarations: List<Dmc> = emptyList(),
        val busy: Boolean = false,
        val error: ImportsError? = null,
        val fetched: String? = null,
    ) : ImportsUiState
}

/**
 * Drives the reading of a customs declaration from the OBR (UC-F03) and the list of the
 * declarations already read.
 */
@HiltViewModel
class ImportsViewModel @Inject constructor(
    private val dmcs: DmcRepository,
    private val fetchDmc: FetchDmc,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<ImportsUiState>(ImportsUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ImportsUiState> = mutableState.asStateFlow()

    /** Reads the declarations kept on the device. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val shown = mutableState.value as? ImportsUiState.Ready ?: ImportsUiState.Ready()
            mutableState.value = storageCall { shown.copy(declarations = dmcs.all()) }
                .getOrElse { ImportsUiState.Failed }
        }
    }

    /** @param reference the new content of the reference field. */
    fun onReferenceChange(reference: String) = update { copy(reference = reference, error = null) }

    /** Asks the OBR for the declaration typed. */
    fun fetch() {
        val ready = (mutableState.value as? ImportsUiState.Ready)?.takeUnless { it.busy } ?: return
        val actor = sessions.current.value?.userId ?: return
        mutableState.value = ready.copy(busy = true, error = null)
        viewModelScope.launch {
            val idle = ready.copy(busy = false)
            val result = storageCall { fetchDmc(ready.reference, actor) }.getOrNull()
            mutableState.value = if (result is FetchDmc.Result.Fetched) {
                idle.copy(reference = "", fetched = result.dmc.reference, declarations = dmcs.all())
            } else {
                idle.copy(error = result?.error() ?: ImportsError.Failed)
            }
        }
    }

    /** The declaration just read was opened. */
    fun onOpened() = update { copy(fetched = null) }

    private fun update(change: ImportsUiState.Ready.() -> ImportsUiState.Ready) {
        val ready = mutableState.value as? ImportsUiState.Ready ?: return
        mutableState.value = ready.change()
    }
}

/** @return why the reading gave nothing, as the screen says it. */
private fun FetchDmc.Result.error(): ImportsError? = when (this) {
    is FetchDmc.Result.Fetched -> null
    FetchDmc.Result.InvalidReference -> ImportsError.InvalidReference
    is FetchDmc.Result.Refused -> ImportsError.Refused(message)
    FetchDmc.Result.Empty -> ImportsError.Empty
    is FetchDmc.Result.Unavailable -> ImportsError.Unavailable
    FetchDmc.Result.NotConfigured -> ImportsError.NotConfigured
}

/** State of a customs declaration. */
sealed interface DmcUiState {
    /** The declaration is being read. */
    data object Loading : DmcUiState

    /** The declaration could not be read. */
    data object Failed : DmcUiState

    /** The declaration is not on this device. */
    data object NotFound : DmcUiState

    /** The declaration is shown with what was entered of each line. */
    data class Ready(val progress: DmcProgress) : DmcUiState
}

/** Drives the lines of a customs declaration and what was entered of them (UC-F04). */
@HiltViewModel
class DmcViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val readDmc: ReadDmc) : ViewModel() {

    /** The reference of the declaration shown. */
    val reference: String = savedStateHandle.toRoute<DmcDestination>().reference

    private val mutableState = MutableStateFlow<DmcUiState>(DmcUiState.Loading)

    /** The state to show. */
    val state: StateFlow<DmcUiState> = mutableState.asStateFlow()

    /** Reads the declaration. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { readDmc(reference)?.let { DmcUiState.Ready(it) } ?: DmcUiState.NotFound }
                .getOrElse { DmcUiState.Failed }
        }
    }
}
