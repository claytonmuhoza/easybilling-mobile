package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.testing.perUnit
import android.app.Application
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxProblem
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.invoicing.LineInput
import com.claytonmuhoza.easybilling.domain.invoicing.ObrLineTaxCalculator
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.taxes.LinePreviewActions
import com.claytonmuhoza.easybilling.taxes.LinePreviewScreen
import com.claytonmuhoza.easybilling.taxes.LinePreviewUiState
import com.claytonmuhoza.easybilling.taxes.PreviewError
import com.claytonmuhoza.easybilling.taxes.PreviewInputs
import com.claytonmuhoza.easybilling.taxes.RateFields
import com.claytonmuhoza.easybilling.taxes.TaxEditorActions
import com.claytonmuhoza.easybilling.taxes.TaxEditorScreen
import com.claytonmuhoza.easybilling.taxes.TaxEditorUiState
import com.claytonmuhoza.easybilling.taxes.TaxListActions
import com.claytonmuhoza.easybilling.taxes.TaxListScreen
import com.claytonmuhoza.easybilling.taxes.TaxListUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test

/** Screenshots of the taxes and of the preview of a line, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TaxScreensScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val today = LocalDate.of(2026, 10, 3)

    private fun percent(rate: String, base: PercentageBase = PercentageBase.PRICE_WITHOUT_VAT) =
        TaxComputation.Percentage(BigDecimal(rate), base)

    private val vat = TaxDefinition(
        id = TaxId(1),
        kind = TaxKind.VAT,
        name = "TVA 18 %",
        versions = listOf(
            TaxVersion(LocalDate.of(2026, 1, 1), percent("18")),
            TaxVersion(LocalDate.of(2027, 1, 1), percent("16")),
        ),
    )
    private val taxes = listOf(
        vat,
        TaxDefinition(TaxId(2), TaxKind.CONSUMPTION_TAX, "TC bière", listOf(TaxVersion(today, perUnit("3")))),
        TaxDefinition(
            TaxId(3),
            TaxKind.FLAT_RATE_WITHHOLDING,
            "PFL 1 %",
            listOf(TaxVersion(today, percent("1", PercentageBase.PRICE_WITH_VAT))),
        ),
        TaxDefinition(
            TaxId(4),
            TaxKind.OTT,
            "OTT",
            listOf(TaxVersion(today, TaxComputation.FreeAmount)),
            archived = true,
        ),
    )

    @Test
    fun taxList() = capture("tax_list") {
        TaxListScreen(state = TaxListUiState.Ready(taxes, today), actions = TaxListActions())
    }

    @Test
    fun taxListEmpty() = capture("tax_list_empty") {
        TaxListScreen(state = TaxListUiState.Ready(emptyList(), today), actions = TaxListActions())
    }

    @Test
    fun taxListLoading() = capture("tax_list_loading", animated = true) {
        TaxListScreen(state = TaxListUiState.Loading, actions = TaxListActions())
    }

    @Test
    fun taxListError() = capture("tax_list_error") {
        TaxListScreen(state = TaxListUiState.Failed, actions = TaxListActions())
    }

    @Test
    fun newTax() = capture("tax_new") {
        TaxEditorScreen(
            state = TaxEditorUiState.Editing(
                existing = null,
                today = today,
                kind = TaxKind.FLAT_RATE_WITHHOLDING,
                name = "PFL 1 %",
                rate = RateFields(
                    mode = TaxMode.PERCENTAGE,
                    value = "1",
                    base = PercentageBase.PRICE_WITH_VAT,
                    validFrom = "03/10/2026",
                ),
            ),
            actions = TaxEditorActions(),
        )
    }

    @Test
    fun newTaxWithErrors() = capture("tax_new_errors") {
        TaxEditorScreen(
            state = TaxEditorUiState.Editing(
                existing = null,
                today = today,
                kind = TaxKind.CONSUMPTION_TAX,
                rate = RateFields(mode = TaxMode.PERCENTAGE, value = "120", validFrom = "01/01/2020"),
                problems = setOf(TaxProblem.NAME_MISSING, TaxProblem.RATE_ABOVE_100, TaxProblem.DATE_IN_PAST),
            ),
            actions = TaxEditorActions(),
        )
    }

    @Test
    fun existingTax() = capture("tax_existing") {
        TaxEditorScreen(
            state = TaxEditorUiState.Editing(
                existing = vat,
                today = today,
                kind = TaxKind.VAT,
                name = vat.name,
                rate = RateFields(mode = TaxMode.PERCENTAGE),
            ),
            actions = TaxEditorActions(),
        )
    }

    @Test
    fun linePreview() = capture("line_preview") {
        val inputs = PreviewInputs(
            unitPrice = "200",
            quantity = "10",
            selected = mapOf(TaxKind.VAT to TaxId(1), TaxKind.CONSUMPTION_TAX to TaxId(2)),
        )
        val result = ObrLineTaxCalculator().compute(
            LineInput(
                quantity = Quantity.of("10"),
                unitPrice = Money.of("200", Currency.BIF),
                priceMode = PriceMode.EXCLUDING_TAXES,
                taxes = mapOf(TaxKind.VAT to percent("18"), TaxKind.CONSUMPTION_TAX to perUnit("3")),
            ),
            TaxRegistrations(vat = true, consumptionTax = true, flatRateWithholding = true),
        )
        LinePreviewScreen(
            state = LinePreviewUiState.Ready(
                taxes.filterNot { it.archived },
                today,
                registrations = null,
                inputs,
                result,
            ),
            actions = LinePreviewActions(),
        )
    }

    @Test
    fun linePreviewNotCovered() = capture("line_preview_not_covered") {
        LinePreviewScreen(
            state = LinePreviewUiState.Ready(
                taxes = taxes.filterNot { it.archived },
                today = today,
                registrations = TaxRegistrations(vat = true),
                inputs = PreviewInputs(unitPrice = "1", priceMode = PriceMode.INCLUDING_TAXES),
                error = PreviewError.NOT_COVERED,
            ),
            actions = LinePreviewActions(),
        )
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
