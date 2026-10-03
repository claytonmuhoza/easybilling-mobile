package com.claytonmuhoza.easybilling.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
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

/** Where the connection to the EBMS stands, as summed up on the home screen. */
sealed interface EbmsStatus {
    /** The settings are being read. */
    data object Loading : EbmsStatus

    /** The settings could not be read. */
    data object Unreadable : EbmsStatus

    /** The active environment has no address or no credentials. */
    data object ToConfigure : EbmsStatus

    /**
     * The active environment is configured.
     *
     * @property environment the active environment.
     * @property lastSuccess when a login last succeeded with its credentials, or null.
     */
    data class Configured(val environment: EbmsEnvironment, val lastSuccess: Instant?) : EbmsStatus
}

/** Where the taxes stand, as summed up on the home screen. */
sealed interface TaxesStatus {
    /** The taxes are being read. */
    data object Loading : TaxesStatus

    /** The taxes could not be read. */
    data object Unreadable : TaxesStatus

    /** [active] taxes may be chosen: not archived. */
    data class Defined(val active: Int) : TaxesStatus
}

/** Where the numbering stands, as summed up on the home screen. */
sealed interface NumberingStatus {
    /** The numbering is being read. */
    data object Loading : NumberingStatus

    /** The numbering could not be read. */
    data object Unreadable : NumberingStatus

    /** No numbering was ever saved. */
    data object ToDefine : NumberingStatus

    /**
     * A numbering is saved.
     *
     * @property example the first number of an invoice with this numbering.
     * @property locked whether a number was already given.
     */
    data class Defined(val example: String, val locked: Boolean) : NumberingStatus
}

/** What is left to configure before the first invoice, as listed on the home screen. */
sealed interface ReadinessStatus {
    /** The configuration is being read. */
    data object Loading : ReadinessStatus

    /** The configuration could not be read. */
    data object Unreadable : ReadinessStatus

    /** The steps and whether each one is done. */
    data class Known(val readiness: BillingReadiness) : ReadinessStatus
}

/** Drives the home screen. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val taxes: TaxRepository,
    private val numbering: NumberingRepository,
    private val checkReadiness: CheckBillingReadiness,
    private val time: TimeProvider,
) : ViewModel() {

    private val mutableTaxProfileStatus = MutableStateFlow<TaxProfileStatus>(TaxProfileStatus.Loading)
    private val mutableEbmsStatus = MutableStateFlow<EbmsStatus>(EbmsStatus.Loading)
    private val mutableTaxesStatus = MutableStateFlow<TaxesStatus>(TaxesStatus.Loading)
    private val mutableNumberingStatus = MutableStateFlow<NumberingStatus>(NumberingStatus.Loading)
    private val mutableReadiness = MutableStateFlow<ReadinessStatus>(ReadinessStatus.Loading)

    /** Where the tax profile stands. */
    val taxProfileStatus: StateFlow<TaxProfileStatus> = mutableTaxProfileStatus.asStateFlow()

    /** Where the connection to the EBMS stands. */
    val ebmsStatus: StateFlow<EbmsStatus> = mutableEbmsStatus.asStateFlow()

    /** Where the taxes stand. */
    val taxesStatus: StateFlow<TaxesStatus> = mutableTaxesStatus.asStateFlow()

    /** Where the numbering stands. */
    val numberingStatus: StateFlow<NumberingStatus> = mutableNumberingStatus.asStateFlow()

    /** What is left to configure before the first invoice. */
    val readiness: StateFlow<ReadinessStatus> = mutableReadiness.asStateFlow()

    /** Reads the state of the settings. Called each time the screen is shown. */
    fun load() {
        viewModelScope.launch {
            // Read first: its sentence is the only text whose height changes once loaded.
            mutableReadiness.value = storageCall { ReadinessStatus.Known(checkReadiness()) }
                .getOrElse { ReadinessStatus.Unreadable }
            mutableTaxProfileStatus.value = storageCall { profiles.current() }.fold(
                onSuccess = { current ->
                    current?.let { TaxProfileStatus.Saved(it.number) } ?: TaxProfileStatus.ToComplete
                },
                onFailure = { TaxProfileStatus.Unreadable },
            )
            mutableEbmsStatus.value = storageCall {
                ebmsSettings.activeLoginTarget(vault)?.let { target ->
                    val lastSuccess = ebmsSettings.read().lastSuccessfulLogin(target.environment)
                    EbmsStatus.Configured(target.environment, lastSuccess)
                } ?: EbmsStatus.ToConfigure
            }.getOrElse { EbmsStatus.Unreadable }
            mutableTaxesStatus.value = storageCall { TaxesStatus.Defined(taxes.all().count { !it.archived }) }
                .getOrElse { TaxesStatus.Unreadable }
            mutableNumberingStatus.value = storageCall { numberingStatus() }.getOrElse { NumberingStatus.Unreadable }
        }
    }

    private suspend fun numberingStatus(): NumberingStatus {
        val settings = numbering.settings() ?: return NumberingStatus.ToDefine
        val year = time.now().atZone(BusinessCalendar.ZONE).year
        return NumberingStatus.Defined(settings.render(DocumentType.FN, year, counter = 1), settings.locked)
    }
}
