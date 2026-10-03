package com.claytonmuhoza.easybilling

import android.app.Application
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemProblem
import com.claytonmuhoza.easybilling.domain.catalog.ItemRules
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.items.ItemEditorActions
import com.claytonmuhoza.easybilling.items.ItemEditorScreen
import com.claytonmuhoza.easybilling.items.ItemEditorUiState
import com.claytonmuhoza.easybilling.items.ItemListActions
import com.claytonmuhoza.easybilling.items.ItemListScreen
import com.claytonmuhoza.easybilling.items.ItemListUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test

/** Screenshots of the list of the items and of the item editor, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ItemScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val day = LocalDate.of(2026, 10, 3)

    private fun vat(id: Long, rate: String) = TaxDefinition(
        TaxId(id),
        TaxKind.VAT,
        "TVA $rate %",
        listOf(TaxVersion(day, TaxComputation.Percentage(BigDecimal(rate), PercentageBase.PRICE_WITHOUT_VAT))),
    )

    private val taxes = listOf(
        vat(1, "18"),
        vat(2, "0"),
        TaxDefinition(TaxId(3), TaxKind.OTT, "OTT", listOf(TaxVersion(day, TaxComputation.FreeAmount))),
    )
    private val registered = ItemRules(TaxRegistrations(vat = true), devicePrefix = "A1", taxes = taxes)
    private val notRegistered = ItemRules(TaxRegistrations(), devicePrefix = null, taxes = emptyList())

    private fun item(id: Long, designation: String, price: String, stockTracked: Boolean = true, vat: Long? = 1) = Item(
        id = ItemId(id),
        code = "A1-ART-%04d".format(id),
        details = ItemDetails(
            designation = designation,
            unit = if (stockTracked) "pièce" else "heure",
            price = Money.of(price, Currency.BIF),
            priceMode = if (stockTracked) PriceMode.INCLUDING_TAXES else PriceMode.EXCLUDING_TAXES,
            taxes = listOfNotNull(vat?.let { TaxKind.VAT to TaxId(it) }).toMap(),
            stockTracked = stockTracked,
        ),
    )

    private val soap = item(1, "Savon de Marseille", "1500").let {
        it.copy(details = it.details.copy(barcode = "6001234567890"))
    }
    private val catalogue = listOf(
        item(2, "Café moulu 250 g", "8500.5"),
        item(3, "Réparation à domicile", "20000", stockTracked = false),
        soap,
        item(4, "Sucre 1 kg", "3200", vat = null),
    )

    private fun ready(items: List<Item>, query: String = "", showArchived: Boolean = false, archived: Int = 0) =
        ItemListUiState.Ready(
            query = query,
            showArchived = showArchived,
            items = items,
            activeCount = catalogue.size,
            archivedCount = archived,
            registrations = registered.registrations,
        )

    @Test
    fun itemList() = capture("item_list") {
        ItemListScreen(state = ready(catalogue), actions = ItemListActions())
    }

    @Test
    fun itemListNoMatch() = capture("item_list_no_match") {
        ItemListScreen(state = ready(emptyList(), query = "thé vert"), actions = ItemListActions())
    }

    @Test
    fun itemListArchived() = capture("item_list_archived") {
        val archived = soap.copy(archived = true)
        ItemListScreen(state = ready(listOf(archived), showArchived = true, archived = 1), actions = ItemListActions())
    }

    @Test
    fun itemListEmpty() = capture("item_list_empty") {
        val empty = ItemListUiState.Ready("", false, emptyList(), 0, 0, TaxRegistrations())
        ItemListScreen(state = empty, actions = ItemListActions())
    }

    @Test
    fun itemListLoading() = capture("item_list_loading", animated = true) {
        ItemListScreen(state = ItemListUiState.Loading, actions = ItemListActions())
    }

    @Test
    fun itemListError() = capture("item_list_error") {
        ItemListScreen(state = ItemListUiState.Failed, actions = ItemListActions())
    }

    @Test
    fun newItem() = capture("item_new") {
        ItemEditorScreen(
            state = ItemEditorUiState.Editing(
                existing = null,
                form = ItemForm(code = "ART-0001"),
                rules = notRegistered,
            ),
            actions = ItemEditorActions(),
        )
    }

    @Test
    fun newItemWithErrors() = capture("item_new_errors") {
        ItemEditorScreen(
            state = ItemEditorUiState.Editing(
                existing = null,
                form = ItemForm(code = "ART-0005", unit = "pièce", price = "0"),
                rules = registered,
                problems = setOf(
                    ItemProblem.CODE_DEVICE_PREFIX_MISSING,
                    ItemProblem.DESIGNATION_MISSING,
                    ItemProblem.PRICE_INVALID,
                    ItemProblem.PRICE_MODE_MISSING,
                    ItemProblem.STOCK_CHOICE_MISSING,
                    ItemProblem.VAT_MISSING,
                ),
                failed = true,
            ),
            actions = ItemEditorActions(),
        )
    }

    @Test
    fun existingItem() = capture("item_existing") {
        ItemEditorScreen(
            state = ItemEditorUiState.Editing(existing = soap, form = ItemForm.from(soap), rules = registered),
            actions = ItemEditorActions(),
        )
    }

    @Test
    fun existingItemNeedingVat() = capture("item_needs_vat") {
        val sugar = catalogue.last().copy(archived = true)
        ItemEditorScreen(
            state = ItemEditorUiState.Editing(existing = sugar, form = ItemForm.from(sugar), rules = registered),
            actions = ItemEditorActions(),
        )
    }

    @Test
    fun itemNotFound() = capture("item_not_found") {
        ItemEditorScreen(state = ItemEditorUiState.NotFound, actions = ItemEditorActions())
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
