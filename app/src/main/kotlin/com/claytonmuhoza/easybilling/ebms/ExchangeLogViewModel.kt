package com.claytonmuhoza.easybilling.ebms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeJournal
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.domain.transmission.SetExchangeRetention
import com.claytonmuhoza.easybilling.navigation.ExchangeDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The editor that changes how long the exchanges are kept.
 *
 * @property days the number of days typed so far.
 * @property invalid true if the last number typed was refused.
 */
data class RetentionEditor(val days: String, val invalid: Boolean = false)

/** State of the log of the exchanges. */
sealed interface ExchangeLogUiState {
    /** The log is being read. */
    data object Loading : ExchangeLogUiState

    /** The log could not be read. */
    data object Failed : ExchangeLogUiState

    /**
     * The log is shown.
     *
     * @property exchanges the most recent exchanges, newest first.
     * @property retentionDays how many days the exchanges are kept.
     * @property retentionEditor the open retention editor, or null.
     */
    data class Ready(
        val exchanges: List<ObrExchange>,
        val retentionDays: Int,
        val retentionEditor: RetentionEditor? = null,
    ) : ExchangeLogUiState
}

/** Shows the exchanges with the OBR (UC-D08) and how long they are kept. */
@HiltViewModel
class ExchangeLogViewModel @Inject constructor(
    private val log: ExchangeLog,
    private val journal: ExchangeJournal,
    private val settings: EbmsSettingsRepository,
    private val setRetention: SetExchangeRetention,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<ExchangeLogUiState>(ExchangeLogUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ExchangeLogUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Purges the exchanges older than the retention, then reads the most recent ones. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall {
                journal.purge()
                ExchangeLogUiState.Ready(log.recent(SHOWN_EXCHANGES), settings.read().exchangeRetentionDays)
            }.getOrElse { ExchangeLogUiState.Failed }
        }
    }

    /** Opens the retention editor with the current number of days. */
    fun openRetention() = edit { copy(retentionEditor = RetentionEditor(retentionDays.toString())) }

    /** @param days the new content of the number of days. */
    fun onRetentionChange(days: String) = edit {
        copy(retentionEditor = RetentionEditor(days.filter(Char::isDigit).take(MAX_DIGITS)))
    }

    /** Closes the retention editor without change. */
    fun dismissRetention() = edit { copy(retentionEditor = null) }

    /** Saves the typed retention and purges the log at once. */
    fun confirmRetention() {
        val editor = (mutableState.value as? ExchangeLogUiState.Ready)?.retentionEditor
        val actor = sessions.current.value?.userId
        if (editor == null || actor == null) return
        viewModelScope.launch {
            val days = editor.days.toIntOrNull() ?: 0
            when (storageCall { setRetention(days, actor) }.getOrNull()) {
                SetExchangeRetention.Result.Saved -> load()
                else -> edit { copy(retentionEditor = editor.copy(invalid = true)) }
            }
        }
    }

    private fun edit(change: ExchangeLogUiState.Ready.() -> ExchangeLogUiState.Ready) {
        val ready = mutableState.value as? ExchangeLogUiState.Ready ?: return
        mutableState.value = ready.change()
    }

    private companion object {
        /** Exchanges listed at most: enough to follow a day of sales without a slow screen. */
        const val SHOWN_EXCHANGES = 200

        /** Digits of the longest retention, 3650 days. */
        const val MAX_DIGITS = 4
    }
}

/** State of the screen showing one exchange. */
sealed interface ExchangeUiState {
    /** The exchange is being read. */
    data object Loading : ExchangeUiState

    /** The exchange could not be read. */
    data object Failed : ExchangeUiState

    /** The exchange is no longer in the log. */
    data object NotFound : ExchangeUiState

    /** [exchange] is shown. */
    data class Ready(val exchange: ObrExchange) : ExchangeUiState
}

/** Shows one exchange with the OBR, secrets masked. */
@HiltViewModel
class ExchangeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val log: ExchangeLog,
) : ViewModel() {

    private val id: Long = savedStateHandle.toRoute<ExchangeDestination>().id

    private val mutableState = MutableStateFlow<ExchangeUiState>(ExchangeUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ExchangeUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the exchange. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { log.find(id)?.let(ExchangeUiState::Ready) ?: ExchangeUiState.NotFound }
                .getOrElse { ExchangeUiState.Failed }
        }
    }
}
