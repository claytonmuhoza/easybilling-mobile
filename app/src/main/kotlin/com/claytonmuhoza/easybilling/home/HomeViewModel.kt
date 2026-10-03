package com.claytonmuhoza.easybilling.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the tax profile stands, as summed up on the home screen. */
sealed interface TaxProfileStatus {
    /** The profile is being read. */
    data object Loading : TaxProfileStatus

    /** The profile could not be read. */
    data object Unreadable : TaxProfileStatus

    /** No profile was ever saved. */
    data object ToComplete : TaxProfileStatus

    /** The current profile is version [number]. */
    data class Saved(val number: Int) : TaxProfileStatus
}

/** Drives the home screen. */
@HiltViewModel
class HomeViewModel @Inject constructor(private val profiles: TaxProfileRepository) : ViewModel() {

    private val mutableStatus = MutableStateFlow<TaxProfileStatus>(TaxProfileStatus.Loading)

    /** Where the tax profile stands. */
    val taxProfileStatus: StateFlow<TaxProfileStatus> = mutableStatus.asStateFlow()

    /** Reads the state of the settings. Called each time the screen is shown. */
    fun load() {
        viewModelScope.launch {
            mutableStatus.value = storageCall { profiles.current() }.fold(
                onSuccess = { current ->
                    current?.let { TaxProfileStatus.Saved(it.number) } ?: TaxProfileStatus.ToComplete
                },
                onFailure = { TaxProfileStatus.Unreadable },
            )
        }
    }
}
