package com.claytonmuhoza.easybilling.taxes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.configuration.ArchiveTax
import com.claytonmuhoza.easybilling.domain.configuration.CreateTax
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.ScheduleTaxRate
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxProblem
import com.claytonmuhoza.easybilling.domain.configuration.TaxRateForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.navigation.TaxEditorDestination
import com.claytonmuhoza.easybilling.ui.DateInput
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * A rate as typed, the day still as text.
 *
 * @property mode the way of computing.
 * @property value the rate or the amount.
 * @property base what a percentage of PFL applies to.
 * @property validFrom the first day, typed DD/MM/YYYY.
 */
data class RateFields(
    val mode: TaxMode? = null,
    val value: String = "",
    val base: PercentageBase? = null,
    val validFrom: String = "",
) {
    /** @return the rate to check; a day that cannot be read counts as missing. */
    fun toForm(): TaxRateForm = TaxRateForm(mode, value, base, DateInput.parse(validFrom))
}

/** Result of the last action, shown under the button. */
enum class TaxFeedback {
    /** A new rate is stored. */
    SCHEDULED,

    /** The change could not be written. */
    FAILED,
}

/** State of the screen creating or changing a tax. */
sealed interface TaxEditorUiState {
    /** The tax is being read. */
    data object Loading : TaxEditorUiState

    /** The tax could not be read. */
    data object Failed : TaxEditorUiState

    /** There is no tax with that identifier. */
    data object NotFound : TaxEditorUiState

    /** The new tax is stored: the screen can close. */
    data object Created : TaxEditorUiState

    /**
     * The form is shown.
     *
     * @property existing the tax being changed, or null for a new tax.
     * @property today the day of the business.
     * @property kind the chosen kind; fixed for an existing tax.
     * @property name the name typed; fixed for an existing tax.
     * @property rate the first rate of a new tax, or the next rate of an existing one.
     * @property problems the rules broken at the last attempt.
     * @property busy true while a change is written.
     * @property feedback result of the last action, or null.
     */
    data class Editing(
        val existing: TaxDefinition?,
        val today: LocalDate,
        val kind: TaxKind?,
        val name: String = "",
        val rate: RateFields = RateFields(),
        val problems: Set<TaxProblem> = emptySet(),
        val busy: Boolean = false,
        val feedback: TaxFeedback? = null,
    ) : TaxEditorUiState
}

/** Drives the creation of a tax, its next rates and its archiving (UC-A07). */
@HiltViewModel
class TaxEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taxes: TaxRepository,
    private val createTax: CreateTax,
    private val scheduleRate: ScheduleTaxRate,
    private val archiveTax: ArchiveTax,
    private val sessions: SessionManager,
    private val time: TimeProvider,
) : ViewModel() {

    private val id: Long = savedStateHandle.toRoute<TaxEditorDestination>().id

    private val mutableState = MutableStateFlow<TaxEditorUiState>(TaxEditorUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TaxEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the tax, or prepares an empty form for a new one. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val today = BusinessCalendar.today(time)
            mutableState.value = if (id == TaxEditorDestination.NEW_TAX) {
                TaxEditorUiState.Editing(
                    existing = null,
                    today = today,
                    kind = null,
                    rate = RateFields(validFrom = DateInput.format(today)),
                )
            } else {
                storageCall { taxes.find(TaxId(id)) }.fold(
                    onSuccess = { tax -> tax?.let { editing(it, today) } ?: TaxEditorUiState.NotFound },
                    onFailure = { TaxEditorUiState.Failed },
                )
            }
        }
    }

    /** @param kind the kind picked for a new tax; the VAT is always a percentage. */
    fun onKindChange(kind: TaxKind) = edit {
        val vatRate = if (kind == TaxKind.VAT) rate.copy(mode = TaxMode.PERCENTAGE) else rate
        copy(kind = kind, rate = vatRate, problems = emptySet())
    }

    /** @param name the new content of the name field. */
    fun onNameChange(name: String) = edit { copy(name = name, problems = problems - TaxProblemFields.name) }

    /**
     * @param change the change of one field of the rate: way of computing, value, base or first
     * day. The problems of the rate are cleared until the next attempt.
     */
    fun onRateChange(change: RateFields.() -> RateFields) = edit {
        copy(rate = rate.change(), problems = problems - RATE_PROBLEMS)
    }

    /** Creates the new tax, or shows what is wrong. */
    fun create() = act { editing, actor ->
        when (val result = createTax(TaxForm(editing.kind, editing.name, editing.rate.toForm()), actor)) {
            is CreateTax.Result.Created -> TaxEditorUiState.Created
            is CreateTax.Result.Invalid -> editing.copy(busy = false, problems = result.problems)
        }
    }

    /** Stores the next rate of the tax, or shows what is wrong. */
    fun schedule() = act { editing, actor ->
        val tax = checkNotNull(editing.existing)
        when (val result = scheduleRate(tax.id, editing.rate.toForm(), actor)) {
            ScheduleTaxRate.Result.Scheduled -> refreshed(tax.id, editing.today)?.copy(feedback = TaxFeedback.SCHEDULED)
                ?: TaxEditorUiState.NotFound
            is ScheduleTaxRate.Result.Invalid -> editing.copy(busy = false, problems = result.problems)
            ScheduleTaxRate.Result.NotFound -> TaxEditorUiState.NotFound
        }
    }

    /** Archives the tax, or brings it back. */
    fun toggleArchived() = act { editing, actor ->
        val tax = checkNotNull(editing.existing)
        archiveTax(tax.id, !tax.archived, actor)
        refreshed(tax.id, editing.today) ?: TaxEditorUiState.NotFound
    }

    private fun act(block: suspend (TaxEditorUiState.Editing, UserId) -> TaxEditorUiState) {
        val editing = mutableState.value as? TaxEditorUiState.Editing ?: return
        val actor = sessions.current.value?.userId
        if (editing.busy || actor == null) return
        mutableState.value = editing.copy(busy = true, feedback = null)
        viewModelScope.launch {
            mutableState.value = storageCall { block(editing, actor) }
                .getOrElse { editing.copy(busy = false, feedback = TaxFeedback.FAILED) }
        }
    }

    private suspend fun refreshed(id: TaxId, today: LocalDate): TaxEditorUiState.Editing? =
        taxes.find(id)?.let { editing(it, today) }

    private fun editing(tax: TaxDefinition, today: LocalDate) = TaxEditorUiState.Editing(
        existing = tax,
        today = today,
        kind = tax.kind,
        name = tax.name,
        rate = RateFields(mode = if (tax.kind == TaxKind.VAT) TaxMode.PERCENTAGE else null),
    )

    private fun edit(change: TaxEditorUiState.Editing.() -> TaxEditorUiState.Editing) {
        val editing = mutableState.value as? TaxEditorUiState.Editing ?: return
        mutableState.value = editing.change()
    }

    private companion object {
        /** The problems a change of the rate clears. */
        val RATE_PROBLEMS =
            TaxProblemFields.mode + TaxProblemFields.value + TaxProblemFields.base + TaxProblemFields.date
    }
}
