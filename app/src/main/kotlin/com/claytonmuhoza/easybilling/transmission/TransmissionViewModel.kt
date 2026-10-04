package com.claytonmuhoza.easybilling.transmission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.transmission.NetworkMonitor
import com.claytonmuhoza.easybilling.domain.transmission.ReadTransmissionStatus
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionStatus
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Where the transmission to the OBR stands, as the banner and the transmission screen show it. */
sealed interface TransmissionUiState {
    /** The state is being read. */
    data object Loading : TransmissionUiState

    /** The state could not be read. */
    data object Failed : TransmissionUiState

    /**
     * The state is shown.
     *
     * @property status the queue, the refused invoices and the invoices acknowledged today.
     * @property online whether the device has a network.
     * @property offlineSince since when it has none, or null.
     */
    data class Ready(
        val status: TransmissionStatus,
        val online: Boolean = true,
        val offlineSince: Instant? = null,
    ) : TransmissionUiState
}

/**
 * Follows the transmission to the OBR (UC-D03) and forces a pass on request (UC-D05). The state is
 * read again each time a pass over the queue ends and each time the network changes.
 */
@HiltViewModel
class TransmissionViewModel @Inject constructor(
    private val readStatus: ReadTransmissionStatus,
    private val monitor: NetworkMonitor,
    private val runner: TransmissionRunner,
) : ViewModel() {

    private val mutableState = MutableStateFlow<TransmissionUiState>(TransmissionUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TransmissionUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(runner.passes, monitor.online, runner.offlineSince) { _, online, since -> online to since }
                .collect { (online, since) -> read(online, since) }
        }
    }

    /** Reads the state again. Also used to retry. */
    fun load() {
        viewModelScope.launch { read(monitor.online.value, runner.offlineSince.value) }
    }

    /** Tries the whole queue now, whatever the wait of each document. */
    fun retryNow() = runner.kick(force = true)

    private suspend fun read(online: Boolean, offlineSince: Instant?) {
        mutableState.value = storageCall { TransmissionUiState.Ready(readStatus(), online, offlineSince) }
            .getOrElse { TransmissionUiState.Failed }
    }
}
