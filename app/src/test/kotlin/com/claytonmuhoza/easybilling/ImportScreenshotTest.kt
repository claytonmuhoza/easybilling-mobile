package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.stock.DmcLineProgress
import com.claytonmuhoza.easybilling.domain.stock.DmcProgress
import com.claytonmuhoza.easybilling.domain.stock.ImportProblem
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.stock.DmcScreen
import com.claytonmuhoza.easybilling.stock.DmcUiState
import com.claytonmuhoza.easybilling.stock.ImportEntryActions
import com.claytonmuhoza.easybilling.stock.ImportEntryScreen
import com.claytonmuhoza.easybilling.stock.ImportEntryUiState
import com.claytonmuhoza.easybilling.stock.ImportsActions
import com.claytonmuhoza.easybilling.stock.ImportsError
import com.claytonmuhoza.easybilling.stock.ImportsScreen
import com.claytonmuhoza.easybilling.stock.ImportsUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test

/**
 * Screenshots of the imported goods (UC-F03, F04): the declarations, the lines of one and the
 * entry of a line into the stock, in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ImportScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = TransmissionSamples.NOW

    private fun item(id: Long, designation: String, unit: String) = Item(
        id = ItemId(id),
        code = "ART-%04d".format(id),
        details = ItemDetails(
            designation = designation,
            unit = unit,
            price = Money.of("60000", Currency.BIF),
            priceMode = PriceMode.INCLUDING_TAXES,
            taxes = emptyMap(),
            stockTracked = true,
        ),
    )

    private val oil = item(2, "Huile de palme raffinée, bidon de 20 litres", "bidon")
    private val rice = item(3, "Riz blanchi, sac de 25 kg", "sac")

    private val machines = DmcLine(
        rank = 1,
        tariffHeading = "84412000000",
        description = "Machines pour la fabrication de sacs, sachets ou enveloppes",
        quantity = BigDecimal("10"),
        packaging = "Sacs",
    )
    private val oilLine = DmcLine(2, "15119090000", "Huile de palme raffinée", BigDecimal("300"), "Jerrycans")
    private val riceLine = DmcLine(3, "10063000000", "Riz blanchi", null, "Sacs")

    private val dmc =
        Dmc("2025BIPORC7090", "4000007155", now - Duration.ofHours(2), listOf(machines, oilLine, riceLine))
    private val other = Dmc("2025BIPORC6411", "4000007155", now - Duration.ofDays(9), listOf(riceLine))

    private val oilProgress = DmcLineProgress(oilLine, BigDecimal("120"), oil)

    private val progress = DmcProgress(
        dmc,
        listOf(
            DmcLineProgress(machines, BigDecimal.ZERO, item = null),
            oilProgress,
            DmcLineProgress(riceLine, BigDecimal("40"), rice),
        ),
    )

    private fun form() = ImportEntryUiState.Form(
        reference = dmc.reference,
        line = oilProgress,
        item = oil,
        quantity = "60",
        unitCost = "45000",
        piecesPerPacket = "1",
        packaging = "Jerrycans",
    )

    @Composable
    private fun imports(state: ImportsUiState) = ImportsScreen(state, ImportsActions())

    @Composable
    private fun declaration(state: DmcUiState) = DmcScreen(dmc.reference, state, onBack = {}, onEnter = {})

    @Composable
    private fun entry(state: ImportEntryUiState) = ImportEntryScreen(state, ImportEntryActions())

    @Test
    fun imports() = capture("imports") {
        imports(ImportsUiState.Ready(declarations = listOf(dmc, other)))
    }

    @Test
    fun importsEmpty() = capture("imports_empty") {
        imports(ImportsUiState.Ready())
    }

    @Test
    fun importsFetching() = capture("imports_fetching") {
        imports(ImportsUiState.Ready(reference = "2025BIPORC7090", declarations = listOf(other), busy = true))
    }

    @Test
    fun importsRefused() = capture("imports_refused") {
        val error = ImportsError.Refused("La reference DMC n'appartient pas a ce NIF")
        imports(ImportsUiState.Ready(reference = "2025BIPORC7091", declarations = listOf(dmc), error = error))
    }

    @Test
    fun importsUnavailable() = capture("imports_unavailable") {
        imports(ImportsUiState.Ready(reference = "2025BIPORC7090", error = ImportsError.Unavailable))
    }

    @Test
    fun importsLoading() = capture("imports_loading", animated = true) {
        imports(ImportsUiState.Loading)
    }

    @Test
    fun importsError() = capture("imports_error") {
        imports(ImportsUiState.Failed)
    }

    @Test
    fun dmc() = capture("dmc") {
        declaration(DmcUiState.Ready(progress))
    }

    @Test
    fun dmcComplete() = capture("dmc_complete") {
        val complete = DmcLineProgress(oilLine, BigDecimal("300"), oil)
        declaration(DmcUiState.Ready(DmcProgress(dmc, listOf(complete))))
    }

    @Test
    fun dmcNotFound() = capture("dmc_not_found") {
        declaration(DmcUiState.NotFound)
    }

    @Test
    fun dmcLoading() = capture("dmc_loading", animated = true) {
        declaration(DmcUiState.Loading)
    }

    @Test
    fun importEntry() = capture("import_entry") {
        entry(form())
    }

    @Test
    fun importEntryChoosingItem() = capture("import_entry_choosing_item") {
        entry(form().copy(item = null, query = "", matches = listOf(oil, rice), quantity = "", unitCost = ""))
    }

    @Test
    fun importEntryNoItem() = capture("import_entry_no_item") {
        val problems = setOf(ImportProblem.ITEM_MISSING)
        entry(form().copy(item = null, query = "sucre", quantity = "", unitCost = "", problems = problems))
    }

    @Test
    fun importEntryProblems() = capture("import_entry_problems") {
        val problems = ImportProblem.entries.toSet() - ImportProblem.ITEM_MISSING - ImportProblem.PACKAGING_MISSING
        entry(form().copy(quantity = "0", unitCost = "abc", piecesPerPacket = "", problems = problems))
    }

    @Test
    fun importEntryExceeds() = capture("import_entry_exceeds") {
        entry(form().copy(quantity = "200", exceeds = BigDecimal("180")))
    }

    @Test
    fun importEntryRefused() = capture("import_entry_refused") {
        entry(form().copy(refused = StockProblem.ITEM_ARCHIVED))
    }

    @Test
    fun importEntryLineGone() = capture("import_entry_line_gone") {
        entry(ImportEntryUiState.LineNotFound)
    }

    @Test
    fun importEntryError() = capture("import_entry_error") {
        entry(ImportEntryUiState.Failed)
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
