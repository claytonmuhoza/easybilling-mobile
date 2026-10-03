package com.claytonmuhoza.easybilling.taxprofile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.navigation.TaxProfileVersionDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the screen showing one version of the tax profile. */
sealed interface TaxProfileVersionUiState {
    /** The version is being read. */
    data object Loading : TaxProfileVersionUiState

    /** The version could not be read. */
    data object Failed : TaxProfileVersionUiState

    /** There is no version with that number. */
    data object NotFound : TaxProfileVersionUiState

    /**
     * The version is shown, read only.
     *
     * @property version the version.
     * @property authorName name of the user who saved it, or null if that user no longer exists.
     */
    data class Ready(val version: TaxProfileVersion, val authorName: String?) : TaxProfileVersionUiState
}

/** Shows one saved version of the tax profile, without any way to change it. */
@HiltViewModel
class TaxProfileVersionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: TaxProfileRepository,
    private val users: UserRepository,
) : ViewModel() {

    /** Number of the version shown. */
    val number: Int = savedStateHandle.toRoute<TaxProfileVersionDestination>().number

    private val mutableState = MutableStateFlow<TaxProfileVersionUiState>(TaxProfileVersionUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TaxProfileVersionUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the version. Also used to retry. */
    fun load() {
        mutableState.value = TaxProfileVersionUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall {
                profiles.find(number)?.let { TaxProfileVersionUiState.Ready(it, users.find(it.savedBy)?.name) }
                    ?: TaxProfileVersionUiState.NotFound
            }.getOrElse { TaxProfileVersionUiState.Failed }
        }
    }
}
