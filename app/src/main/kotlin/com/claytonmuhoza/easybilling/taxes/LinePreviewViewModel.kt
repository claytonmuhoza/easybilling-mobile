package com.claytonmuhoza.easybilling.taxes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.LineInput
import com.claytonmuhoza.easybilling.domain.invoicing.LineTaxCalculator
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/**
 * What the user typed to preview a line.
 *
 * @property unitPrice the unit price, as typed.
 * @property quantity the quantity, as typed.
 * @property priceMode whether the price includes the taxes.
 * @property currency the currency of the line.
 * @property selected the tax picked for each kind.
 * @property freeAmounts the amount typed for each tax computed as a free amount.
 */
data class PreviewInputs(
    val unitPrice: String = "",
    val quantity: String = "1",
    val priceMode: PriceMode = PriceMode.EXCLUDING_TAXES,
    val currency: Currency = Currency.BIF,
    val selected: Map<TaxKind, TaxId> = emptyMap(),
    val freeAmounts: Map<TaxKind, String> = emptyMap(),
)

/** Why no amount can be shown. */
enum class PreviewError {
    /** A number cannot be read. */
    INVALID_NUMBER,

    /** A price including taxes is smaller than its fixed taxes. */
    NOT_COVERED,
}

/** State of the preview of a line. */
sealed interface LinePreviewUiState {
    /** The taxes are being read. */
    data object Loading : LinePreviewUiState

    /** The taxes could not be read. */
    data object Failed : LinePreviewUiState

    /**
     * The preview is shown.
     *
     * @property taxes the taxes that may be picked today: not archived, with a rate in force.
     * @property today the day of the business, which decides the rates.
     * @property registrations the taxes the business is registered for, or null without profile.
     * @property inputs what the user typed.
     * @property result the amounts of the line, or null until a price is typed.
     * @property error why no amount is shown, or null.
     */
    data class Ready(
        val taxes: List<TaxDefinition>,
        val today: LocalDate,
        val registrations: TaxRegistrations?,
        val inputs: PreviewInputs = PreviewInputs(),
        val result: LineAmounts? = null,
        val error: PreviewError? = null,
    ) : LinePreviewUiState
}

/** Shows how a line is computed with the configured taxes (UC-A07), before any article exists. */
@HiltViewModel
class LinePreviewViewModel @Inject constructor(
    private val taxes: TaxRepository,
    private val profiles: TaxProfileRepository,
    private val calculator: LineTaxCalculator,
    private val time: TimeProvider,
) : ViewModel() {

    private val mutableState = MutableStateFlow<LinePreviewUiState>(LinePreviewUiState.Loading)

    /** The state to show. */
    val state: StateFlow<LinePreviewUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the taxes and the registrations of the business. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val today = BusinessCalendar.today(time)
            mutableState.value = storageCall {
                LinePreviewUiState.Ready(
                    taxes = taxes.all().filter { !it.archived && it.computationAt(today) != null },
                    today = today,
                    registrations = profiles.current()?.profile?.taxes,
                )
            }.getOrElse { LinePreviewUiState.Failed }
        }
    }

    /**
     * @param change the change of a field of the line: unit price, quantity, price mode or
     * currency. The amounts are computed again at once.
     */
    fun onInputsChange(change: PreviewInputs.() -> PreviewInputs) = update(change)

    /**
     * @param kind a kind of tax.
     * @param tax the tax picked for it, or null for none.
     */
    fun onTaxSelected(kind: TaxKind, tax: TaxId?) = update {
        copy(selected = if (tax == null) selected - kind else selected + (kind to tax))
    }

    /**
     * @param kind a kind of tax computed as a free amount.
     * @param amount the new content of its amount field.
     */
    fun onFreeAmountChange(kind: TaxKind, amount: String) = update {
        copy(freeAmounts = freeAmounts + (kind to amount))
    }

    private fun update(change: PreviewInputs.() -> PreviewInputs) {
        val ready = mutableState.value as? LinePreviewUiState.Ready ?: return
        val inputs = ready.inputs.change()
        mutableState.value = compute(ready.copy(inputs = inputs))
    }

    private fun compute(state: LinePreviewUiState.Ready): LinePreviewUiState.Ready {
        val line = if (state.inputs.unitPrice.isBlank()) null else lineOf(state)
        return when {
            state.inputs.unitPrice.isBlank() -> state.copy(result = null, error = null)
            line == null -> state.copy(result = null, error = PreviewError.INVALID_NUMBER)
            else -> try {
                state.copy(result = calculator.compute(line, state.registrations ?: ALL_REGISTERED), error = null)
            } catch (_: IllegalArgumentException) {
                state.copy(result = null, error = PreviewError.NOT_COVERED)
            }
        }
    }

    /** @return the line to compute, or null if a number cannot be read. */
    private fun lineOf(state: LinePreviewUiState.Ready): LineInput? {
        val inputs = state.inputs
        val price = number(inputs.unitPrice)
        val quantity = number(inputs.quantity)?.takeIf { it.signum() > 0 }
        val freeAmounts = inputs.freeAmounts.mapValues { (_, text) ->
            if (text.isBlank()) BigDecimal.ZERO else number(text)
        }
        if (price == null || quantity == null || freeAmounts.values.any { it == null }) return null
        return LineInput(
            quantity = Quantity.of(quantity),
            unitPrice = Money.of(price, inputs.currency),
            priceMode = inputs.priceMode,
            taxes = inputs.selected.mapNotNull { (kind, id) -> computationOf(state, id)?.let { kind to it } }.toMap(),
            freeAmounts = freeAmounts.mapValues { Money.of(checkNotNull(it.value), inputs.currency) },
        )
    }

    private fun computationOf(state: LinePreviewUiState.Ready, id: TaxId): TaxComputation? =
        state.taxes.firstOrNull { it.id == id }?.computationAt(state.today)

    private fun number(text: String): BigDecimal? =
        text.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    private companion object {
        /** Without a tax profile, every tax is applied: the preview says so. */
        val ALL_REGISTERED = TaxRegistrations(vat = true, consumptionTax = true, flatRateWithholding = true)
    }
}
