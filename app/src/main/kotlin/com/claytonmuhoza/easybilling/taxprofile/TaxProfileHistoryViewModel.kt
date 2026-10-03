package com.claytonmuhoza.easybilling.taxprofile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * A version of the tax profile as listed in the history.
 *
 * @property number number of the version.
 * @property savedAt when it was saved.
 * @property authorName name of the user who saved it, or null if that user no longer exists.
 */
data class VersionSummary(val number: Int, val savedAt: Instant, val authorName: String?)

/** State of the tax profile history. */
sealed interface TaxProfileHistoryUiState {
    /** The history is being read. */
    data object Loading : TaxProfileHistoryUiState

    /** The history could not be read. */
    data object Failed : TaxProfileHistoryUiState

    /** [versions] are shown, newest first; the list is empty before the first save. */
    data class Ready(val versions: List<VersionSummary>) : TaxProfileHistoryUiState
}

/** Lists the saved versions of the tax profile. */
@HiltViewModel
class TaxProfileHistoryViewModel @Inject constructor(
    private val profiles: TaxProfileRepository,
    private val users: UserRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow<TaxProfileHistoryUiState>(TaxProfileHistoryUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TaxProfileHistoryUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the history. Also used to retry. */
    fun load() {
        mutableState.value = TaxProfileHistoryUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall { profiles.history().map { it.summary() } }.fold(
                onSuccess = { TaxProfileHistoryUiState.Ready(it) },
                onFailure = { TaxProfileHistoryUiState.Failed },
            )
        }
    }

    private suspend fun TaxProfileVersion.summary() = VersionSummary(number, savedAt, users.find(savedBy)?.name)
}
