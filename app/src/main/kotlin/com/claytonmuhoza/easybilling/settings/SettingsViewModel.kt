package com.claytonmuhoza.easybilling.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.home.EbmsStatus
import com.claytonmuhoza.easybilling.home.HomeStatusReader
import com.claytonmuhoza.easybilling.home.NumberingStatus
import com.claytonmuhoza.easybilling.home.ReadinessStatus
import com.claytonmuhoza.easybilling.home.TaxProfileStatus
import com.claytonmuhoza.easybilling.home.TaxesStatus
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where each setting stands, as the settings screen sums it up.
 *
 * @property readiness what is left to configure before the first invoice.
 * @property taxProfileStatus where the tax profile stands.
 * @property ebmsStatus where the connection to the EBMS stands.
 * @property taxesStatus where the taxes stand.
 * @property numberingStatus where the numbering stands.
 */
data class SettingsUiState(
    val readiness: ReadinessStatus = ReadinessStatus.Loading,
    val taxProfileStatus: TaxProfileStatus = TaxProfileStatus.Loading,
    val ebmsStatus: EbmsStatus = EbmsStatus.Loading,
    val taxesStatus: TaxesStatus = TaxesStatus.Loading,
    val numberingStatus: NumberingStatus = NumberingStatus.Loading,
)

/** Reads where each setting stands. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val reader: HomeStatusReader,
    private val checkReadiness: CheckBillingReadiness,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsUiState())

    /** The state to show. */
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    /** Reads the settings. Called each time the screen is shown: a setting may have changed on its own screen. */
    fun load() {
        viewModelScope.launch {
            // Read first: its sentence is the only text whose height changes once loaded.
            val readiness = storageCall { ReadinessStatus.Known(checkReadiness()) }
                .getOrElse { ReadinessStatus.Unreadable }
            mutableState.update { it.copy(readiness = readiness) }
            val taxProfile = storageCall { reader.taxProfile() }.getOrElse { TaxProfileStatus.Unreadable }
            val ebms = storageCall { reader.ebms() }.getOrElse { EbmsStatus.Unreadable }
            val taxes = storageCall { reader.taxes() }.getOrElse { TaxesStatus.Unreadable }
            val numbering = storageCall { reader.numbering() }.getOrElse { NumberingStatus.Unreadable }
            mutableState.update {
                it.copy(
                    taxProfileStatus = taxProfile,
                    ebmsStatus = ebms,
                    taxesStatus = taxes,
                    numberingStatus = numbering,
                )
            }
        }
    }
}
