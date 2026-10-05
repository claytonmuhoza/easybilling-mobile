package com.claytonmuhoza.easybilling.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceFilter
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
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

/** Where the catalogue stands, as summed up on the home screen. */
sealed interface CatalogStatus {
    /** The items are being read. */
    data object Loading : CatalogStatus

    /** The items could not be read. */
    data object Unreadable : CatalogStatus

    /**
     * The items were counted.
     *
     * @property active how many items can be sold: not archived.
     * @property withoutVat how many of them miss the VAT a registered business must charge.
     */
    data class Counted(val active: Int, val withoutVat: Int) : CatalogStatus
}

/** Where the customers stand, as summed up on the home screen. */
sealed interface CustomersStatus {
    /** The customers are being read. */
    data object Loading : CustomersStatus

    /** The customers could not be read. */
    data object Unreadable : CustomersStatus

    /** [active] customers can be chosen at the sale, besides the counter customer. */
    data class Counted(val active: Int) : CustomersStatus
}

/** Where the sales stand, as summed up on the home screen. */
sealed interface SalesStatus {
    /** The sales are being read. */
    data object Loading : SalesStatus

    /** The sales could not be read. */
    data object Unreadable : SalesStatus

    /** [held] sales are on hold. */
    data class Counted(val held: Int) : SalesStatus
}

/**
 * Reads what the home screen sums up.
 *
 * @param profiles where the tax profile is kept.
 * @param ebmsSettings where the settings of the EBMS are kept.
 * @param vault where the EBMS credentials are kept.
 * @param taxes where the taxes are kept.
 * @param numbering where the numbering is kept.
 * @param items where the items are kept.
 * @param customers where the customers are kept.
 * @param drafts where the sales that are not issued are kept.
 * @param time source of the current year.
 */
class HomeStatusReader @Inject constructor(
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val taxes: TaxRepository,
    private val numbering: NumberingRepository,
    private val items: ItemRepository,
    private val customers: CustomerRepository,
    private val drafts: SaleDraftRepository,
    private val time: TimeProvider,
) {
    /** @return where the tax profile stands. */
    suspend fun taxProfile(): TaxProfileStatus =
        profiles.current()?.let { TaxProfileStatus.Saved(it.number) } ?: TaxProfileStatus.ToComplete

    /** @return where the connection to the EBMS stands. */
    suspend fun ebms(): EbmsStatus = ebmsSettings.activeLoginTarget(vault)?.let { target ->
        EbmsStatus.Configured(target.environment, ebmsSettings.read().lastSuccessfulLogin(target.environment))
    } ?: EbmsStatus.ToConfigure

    /** @return where the taxes stand. */
    suspend fun taxes(): TaxesStatus = TaxesStatus.Defined(taxes.all().count { !it.archived })

    /** @return where the numbering stands. */
    suspend fun numbering(): NumberingStatus {
        val settings = numbering.settings() ?: return NumberingStatus.ToDefine
        val year = time.now().atZone(BusinessCalendar.ZONE).year
        return NumberingStatus.Defined(settings.render(DocumentType.FN, year, counter = 1), settings.locked)
    }

    /** @return where the catalogue stands. */
    suspend fun catalog(): CatalogStatus {
        val vatRegistered = profiles.current()?.profile?.taxes?.vat == true
        return CatalogStatus.Counted(
            active = items.count(archived = false),
            withoutVat = if (vatRegistered) items.countWithoutVat() else 0,
        )
    }

    /** @return where the customers stand. */
    suspend fun customers(): CustomersStatus = CustomersStatus.Counted(customers.count(archived = false))

    /** @return where the sales stand. */
    suspend fun sales(): SalesStatus = SalesStatus.Counted(drafts.held().size)
}

/**
 * What the home screen sums up.
 *
 * @property readiness what is left to configure before the first invoice.
 * @property catalogStatus where the catalogue stands.
 * @property customersStatus where the customers stand.
 * @property salesStatus where the sales stand.
 * @property recentInvoices the latest invoices, [HomeViewModel.RECENT_INVOICES] at most; empty
 * while they are read, when there is none, or when they could not be read.
 */
data class HomeUiState(
    val readiness: ReadinessStatus = ReadinessStatus.Loading,
    val catalogStatus: CatalogStatus = CatalogStatus.Loading,
    val customersStatus: CustomersStatus = CustomersStatus.Loading,
    val salesStatus: SalesStatus = SalesStatus.Loading,
    val recentInvoices: List<InvoiceSummary> = emptyList(),
)

/** Drives the home screen. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reader: HomeStatusReader,
    private val checkReadiness: CheckBillingReadiness,
    private val invoices: InvoiceListing,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val mutableState = MutableStateFlow(HomeUiState())

    /** The state to show. */
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        // An invoice sent meanwhile changes status: the latest invoices are read again.
        viewModelScope.launch { transmission.passes.drop(1).collect { readRecentInvoices() } }
    }

    /** Reads what the screen sums up. Called each time the screen is shown. */
    fun load() {
        // Whatever waits in the send queue is tried again each time the home screen is shown.
        transmission.kick()
        viewModelScope.launch {
            // Read first: its notice is the only content whose height changes once loaded.
            val readiness = storageCall { ReadinessStatus.Known(checkReadiness()) }
                .getOrElse { ReadinessStatus.Unreadable }
            mutableState.update { it.copy(readiness = readiness) }
            val catalog = storageCall { reader.catalog() }.getOrElse { CatalogStatus.Unreadable }
            val customers = storageCall { reader.customers() }.getOrElse { CustomersStatus.Unreadable }
            val sales = storageCall { reader.sales() }.getOrElse { SalesStatus.Unreadable }
            mutableState.update { it.copy(catalogStatus = catalog, customersStatus = customers, salesStatus = sales) }
            readRecentInvoices()
        }
    }

    private suspend fun readRecentInvoices() {
        val recent = storageCall { invoices.search(InvoiceFilter(), RECENT_INVOICES) }.getOrElse { emptyList() }
        mutableState.update { it.copy(recentInvoices = recent) }
    }

    /** Limits of the home screen. */
    companion object {
        /** How many of the latest invoices the home screen shows. */
        const val RECENT_INVOICES = 3
    }
}
