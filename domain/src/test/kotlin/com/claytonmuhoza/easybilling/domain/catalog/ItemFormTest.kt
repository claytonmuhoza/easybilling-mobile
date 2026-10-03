package com.claytonmuhoza.easybilling.domain.catalog

import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.testing.percent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Rules of an item of the catalogue (UC-B01). */
class ItemFormTest {

    private val day = LocalDate.of(2026, 10, 3)
    private val vat = TaxDefinition(
        TaxId(1),
        TaxKind.VAT,
        "TVA 18 %",
        listOf(TaxVersion(day, percent("18", PercentageBase.PRICE_WITHOUT_VAT))),
    )
    private val consumption = vat.copy(id = TaxId(2), kind = TaxKind.CONSUMPTION_TAX, name = "TC")
    private val archivedVat = vat.copy(id = TaxId(3), name = "TVA 10 %", archived = true)
    private val taxes = listOf(vat, consumption, archivedVat)

    private val notRegistered = ItemRules(TaxRegistrations(), devicePrefix = null, taxes = taxes)
    private val vatRegistered = notRegistered.copy(registrations = TaxRegistrations(vat = true))

    private val form = ItemForm(
        code = "ART-0001",
        designation = "Savon de Marseille",
        unit = "pièce",
        price = "1500",
        priceMode = PriceMode.EXCLUDING_TAXES,
        stockTracked = true,
    )

    private fun valid(form: ItemForm, rules: ItemRules = notRegistered) = assertIs<ItemCheck.Valid>(form.check(rules))

    private fun problems(form: ItemForm, rules: ItemRules = notRegistered) =
        assertIs<ItemCheck.Invalid>(form.check(rules)).problems

    @Test
    fun `a complete form gives the item, trimmed, with its code in capitals`() {
        val checked = valid(form.copy(code = " art-0001 ", designation = "  Savon de Marseille ", unit = " pièce "))

        assertEquals("ART-0001", checked.code)
        assertEquals(
            ItemDetails(
                designation = "Savon de Marseille",
                unit = "pièce",
                price = Money.of("1500", Currency.BIF),
                priceMode = PriceMode.EXCLUDING_TAXES,
                taxes = emptyMap(),
                stockTracked = true,
            ),
            checked.details,
        )
    }

    @Test
    fun `every missing value is reported at once`() {
        assertEquals(
            setOf(
                ItemProblem.CODE_MISSING,
                ItemProblem.DESIGNATION_MISSING,
                ItemProblem.UNIT_MISSING,
                ItemProblem.PRICE_MISSING,
                ItemProblem.PRICE_MODE_MISSING,
                ItemProblem.STOCK_CHOICE_MISSING,
            ),
            problems(ItemForm(code = " ", designation = " ", unit = "")),
        )
    }

    @Test
    fun `the sizes of the specification are checked on each limit`() {
        // item_code 30, item_designation 500, item_measurement_unit 20.
        valid(form.copy(code = "A".repeat(30), designation = "d".repeat(500), unit = "u".repeat(20)))
        assertEquals(setOf(ItemProblem.CODE_TOO_LONG), problems(form.copy(code = "A".repeat(31))))
        assertEquals(setOf(ItemProblem.DESIGNATION_TOO_LONG), problems(form.copy(designation = "d".repeat(501))))
        assertEquals(setOf(ItemProblem.UNIT_TOO_LONG), problems(form.copy(unit = "u".repeat(21))))
        assertEquals(30, ItemField.CODE.maxLength)
        assertEquals(500, ItemField.DESIGNATION.maxLength)
        assertEquals(20, ItemField.UNIT.maxLength)
    }

    @Test
    fun `a price is above zero with two decimals at most, a comma being a decimal point`() {
        assertEquals(Money.of("1250.5", Currency.BIF), valid(form.copy(price = " 1250,50 ")).details.price)
        for (price in listOf("0", "-5", "abc", "1.234", "1 000")) {
            assertEquals(setOf(ItemProblem.PRICE_INVALID), problems(form.copy(price = price)), price)
        }
    }

    @Test
    fun `with several devices the code starts with the code of the device`() {
        val rules = notRegistered.copy(devicePrefix = "A1")

        assertEquals(setOf(ItemProblem.CODE_DEVICE_PREFIX_MISSING), problems(form, rules))
        assertEquals(setOf(ItemProblem.CODE_DEVICE_PREFIX_MISSING), problems(form.copy(code = "A1ART-0001"), rules))
        assertEquals("A1-ART-0001", valid(form.copy(code = "a1-art-0001"), rules).code)
    }

    @Test
    fun `a taxpayer registered for the VAT chooses a VAT for every item`() {
        assertEquals(setOf(ItemProblem.VAT_MISSING), problems(form, vatRegistered))

        val checked = valid(form.copy(taxes = mapOf(TaxKind.VAT to vat.id)), vatRegistered)
        assertEquals(mapOf(TaxKind.VAT to vat.id), checked.details.taxes)
    }

    @Test
    fun `a chosen tax exists, is of its kind and is not archived`() {
        val refused = listOf(
            mapOf(TaxKind.VAT to TaxId(99)),
            mapOf(TaxKind.VAT to consumption.id),
            mapOf(TaxKind.VAT to archivedVat.id),
        )
        for (choice in refused) {
            assertEquals(setOf(ItemProblem.TAX_NOT_AVAILABLE), problems(form.copy(taxes = choice), vatRegistered))
        }
        valid(form.copy(taxes = mapOf(TaxKind.VAT to vat.id, TaxKind.CONSUMPTION_TAX to consumption.id)), vatRegistered)
    }

    @Test
    fun `the code of an existing item never changes and is not checked again`() {
        val existing = Item(ItemId(7), "OLD-1", valid(form).details)
        val rules = notRegistered.copy(devicePrefix = "A1")

        val checked = assertIs<ItemCheck.Valid>(form.copy(code = "", price = "2000").check(rules, existing))

        assertEquals("OLD-1", checked.code)
        assertEquals(Money.of("2000", Currency.BIF), checked.details.price)
    }

    @Test
    fun `an item without VAT must be completed once the business is registered for the VAT`() {
        val withoutVat = Item(ItemId(1), "ART-0001", valid(form).details)
        val withVat = withoutVat.copy(details = withoutVat.details.copy(taxes = mapOf(TaxKind.VAT to vat.id)))

        assertFalse(withoutVat.needsVat(TaxRegistrations()))
        assertTrue(withoutVat.needsVat(TaxRegistrations(vat = true)))
        assertFalse(withVat.needsVat(TaxRegistrations(vat = true)))
        // The VAT of an item is kept when the business is no longer registered: it is simply not applied.
        assertFalse(withVat.needsVat(TaxRegistrations()))
    }

    @Test
    fun `a form made from an item gives the same item`() {
        val details = valid(form.copy(price = "1250,5", taxes = mapOf(TaxKind.VAT to vat.id)), vatRegistered).details
        val item = Item(ItemId(1), "ART-0001", details)

        val again = assertIs<ItemCheck.Valid>(ItemForm.from(item).check(vatRegistered, item))

        assertEquals(details, again.details)
    }

    @Test
    fun `the search text ignores case, accents and extra spaces`() {
        assertEquals("art-0001 cafe moulu 250 g", SearchText.normalize("  ART-0001   Café MOULU 250 g "))
        assertEquals("ibiharage", SearchText.normalize("Ibiharage"))
        assertEquals("", SearchText.normalize("   "))
    }

    @Test
    fun `a barcode is optional, trimmed and kept as typed`() {
        assertEquals(null, valid(form).details.barcode)
        assertEquals(null, valid(form.copy(barcode = "   ")).details.barcode)
        assertEquals("6001234567890", valid(form.copy(barcode = " 6001234567890 ")).details.barcode)
        assertEquals("abc-12", valid(form.copy(barcode = "abc-12")).details.barcode)
    }

    @Test
    fun `a barcode has 50 characters at most`() {
        valid(form.copy(barcode = "1".repeat(50)))
        assertEquals(setOf(ItemProblem.BARCODE_TOO_LONG), problems(form.copy(barcode = "1".repeat(51))))
        assertEquals(50, ItemForm.MAX_BARCODE_LENGTH)
    }

    @Test
    fun `the barcode of an item comes back in its form`() {
        val item = Item(ItemId(1), "ART-0001", valid(form.copy(barcode = "6001234567890")).details)

        assertEquals("6001234567890", ItemForm.from(item).barcode)
        assertEquals("", ItemForm.from(item.copy(details = item.details.copy(barcode = null))).barcode)
    }
}
