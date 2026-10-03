package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.testing.percent
import com.claytonmuhoza.easybilling.domain.testing.perUnit
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The line calculation imposed by the OBR (`docs/architecture.md` § 7.1 and § 7.2). */
class ObrLineTaxCalculatorTest {

    private val calculator = ObrLineTaxCalculator()
    private val allRegistered = TaxRegistrations(vat = true, consumptionTax = true, flatRateWithholding = true)
    private val vat18 = percent("18", PercentageBase.PRICE_WITHOUT_VAT)

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun line(
        unitPrice: String,
        quantity: String,
        taxes: Map<TaxKind, TaxComputation> = mapOf(TaxKind.VAT to vat18),
        freeAmounts: Map<TaxKind, String> = emptyMap(),
        mode: PriceMode = PriceMode.EXCLUDING_TAXES,
    ) = LineInput(
        quantity = Quantity.of(quantity),
        unitPrice = bif(unitPrice),
        priceMode = mode,
        taxes = taxes,
        freeAmounts = freeAmounts.mapValues { bif(it.value) },
    )

    private fun compute(input: LineInput, registrations: TaxRegistrations = allRegistered) =
        calculator.compute(input, registrations)

    /** Asserts the columns of the OBR: HTVA, TVA, TVAC, total. */
    private fun assertObr(amounts: LineAmounts, nvat: String, vat: String, wvat: String, total: String) {
        assertEquals(bif(nvat), amounts.priceWithoutVat, "item_price_nvat")
        assertEquals(bif(vat), amounts.vat, "vat")
        assertEquals(bif(wvat), amounts.priceWithVat, "item_price_wvat")
        assertEquals(bif(total), amounts.total, "item_total_amount")
    }

    private fun assertConsistent(amounts: LineAmounts) {
        with(amounts) {
            assertEquals(priceWithoutVat, price + consumptionTax + ott + tsce, "HTVA = PU × Q + TC + OTT + TSCE")
            assertEquals(priceWithVat, priceWithoutVat + vat, "TVAC = HTVA + TVA")
            assertEquals(total, priceWithVat + flatRateWithholding, "total = TVAC + PFL")
            listOf(price, consumptionTax, ott, tsce, priceWithoutVat, vat, priceWithVat, flatRateWithholding, total)
                .forEach { assertTrue(it.amount.signum() >= 0 && it.amount.scale() <= 2, "$it") }
        }
    }

    @Test
    fun `first example of the specification - 500 times 10 without other tax`() {
        assertObr(compute(line("500", "10")), nvat = "5000", vat = "900", wvat = "5900", total = "5900")
    }

    @Test
    fun `second example of the specification - 200 times 10 with a TC of 30 and a PFL of 25`() {
        val amounts = compute(
            line(
                "200", "10",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.CONSUMPTION_TAX to TaxComputation.FreeAmount,
                    TaxKind.FLAT_RATE_WITHHOLDING to TaxComputation.FreeAmount,
                ),
                freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to "30", TaxKind.FLAT_RATE_WITHHOLDING to "25"),
            ),
        )

        assertObr(amounts, nvat = "2030", vat = "365.4", wvat = "2395.4", total = "2420.4")
        assertEquals(bif("30"), amounts.consumptionTax)
        assertEquals(bif("25"), amounts.flatRateWithholding)
        assertEquals("365.4", amounts.vat.toPlainString())
    }

    @Test
    fun `third example of the specification - 500 times 10 with a TC of 789 and a PFL of 123`() {
        val amounts = compute(
            line(
                "500", "10",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.CONSUMPTION_TAX to TaxComputation.FreeAmount,
                    TaxKind.FLAT_RATE_WITHHOLDING to TaxComputation.FreeAmount,
                ),
                freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to "789", TaxKind.FLAT_RATE_WITHHOLDING to "123"),
            ),
        )

        assertObr(amounts, nvat = "5789", vat = "1042.02", wvat = "6831.02", total = "6954.02")
    }

    @Test
    fun `fourth example of the specification - 900 times 10 without other tax`() {
        assertObr(compute(line("900", "10")), nvat = "9000", vat = "1620", wvat = "10620", total = "10620")
    }

    @Test
    fun `a TC per unit is multiplied by the quantity`() {
        val amounts = compute(
            line("200", "10", taxes = mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to perUnit("3"))),
        )

        assertEquals(bif("30"), amounts.consumptionTax)
        assertObr(amounts, nvat = "2030", vat = "365.4", wvat = "2395.4", total = "2395.4")
    }

    @Test
    fun `a TC in percent is computed on the price times the quantity`() {
        val ct = percent("1.5", PercentageBase.PRICE)

        val amounts = compute(line("200", "10", taxes = mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to ct)))

        assertEquals(bif("30"), amounts.consumptionTax)
        assertObr(amounts, nvat = "2030", vat = "365.4", wvat = "2395.4", total = "2395.4")
    }

    @Test
    fun `OTT and TSCE behave like the TC - they are added to the price without VAT`() {
        val amounts = compute(
            line(
                "100", "2",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.OTT to perUnit("5"),
                    TaxKind.TSCE to percent("10", PercentageBase.PRICE),
                ),
            ),
        )

        assertEquals(bif("10"), amounts.ott)
        assertEquals(bif("20"), amounts.tsce)
        assertObr(amounts, nvat = "230", vat = "41.4", wvat = "271.4", total = "271.4")
    }

    @Test
    fun `a PFL in percent uses its own base`() {
        fun pfl(base: PercentageBase) = compute(
            line(
                "200", "10",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.CONSUMPTION_TAX to perUnit("3"),
                    TaxKind.FLAT_RATE_WITHHOLDING to percent("1", base),
                ),
            ),
        ).flatRateWithholding

        assertEquals(bif("20"), pfl(PercentageBase.PRICE))
        assertEquals(bif("20.3"), pfl(PercentageBase.PRICE_WITHOUT_VAT))
        assertEquals(bif("23.95"), pfl(PercentageBase.PRICE_WITH_VAT))
    }

    @Test
    fun `a PFL per unit is multiplied by the quantity`() {
        val amounts = compute(
            line("200", "10", taxes = mapOf(TaxKind.VAT to vat18, TaxKind.FLAT_RATE_WITHHOLDING to perUnit("2.5"))),
        )

        assertEquals(bif("25"), amounts.flatRateWithholding)
        assertEquals(bif("2385"), amounts.total)
    }

    @Test
    fun `each amount is rounded to two decimals, a half going up`() {
        val amounts = compute(line("0.15", "1"))

        // 0.15 x 18 % = 0.027: rounded to 0.03.
        assertEquals(bif("0.03"), amounts.vat)
        assertConsistent(amounts)
    }

    @Test
    fun `a decimal quantity is computed exactly`() {
        val amounts = compute(line("1250", "2.5"))

        assertObr(amounts, nvat = "3125", vat = "562.5", wvat = "3687.5", total = "3687.5")
    }

    @Test
    fun `a unit price with more than two decimals is rounded before use`() {
        val amounts = compute(line("10.005", "3"))

        assertEquals(bif("10.01"), amounts.unitPrice)
        assertEquals(bif("30.03"), amounts.price)
    }

    @Test
    fun `a business not registered for VAT charges none`() {
        val amounts = compute(line("500", "10"), allRegistered.copy(vat = false))

        assertObr(amounts, nvat = "5000", vat = "0", wvat = "5000", total = "5000")
    }

    @Test
    fun `a business not registered for the TC nor the PFL charges neither, but still the OTT and the TSCE`() {
        val amounts = compute(
            line(
                "200", "10",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.CONSUMPTION_TAX to perUnit("3"),
                    TaxKind.FLAT_RATE_WITHHOLDING to perUnit("2.5"),
                    TaxKind.OTT to perUnit("1"),
                    TaxKind.TSCE to perUnit("1"),
                ),
            ),
            TaxRegistrations(vat = true, consumptionTax = false, flatRateWithholding = false),
        )

        assertEquals(bif("0"), amounts.consumptionTax)
        assertEquals(bif("0"), amounts.flatRateWithholding)
        assertEquals(bif("10"), amounts.ott)
        assertEquals(bif("10"), amounts.tsce)
        assertObr(amounts, nvat = "2020", vat = "363.6", wvat = "2383.6", total = "2383.6")
    }

    @Test
    fun `a free amount that is not given counts as zero`() {
        val taxes = mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to TaxComputation.FreeAmount)

        val amounts = compute(line("200", "10", taxes = taxes))

        assertEquals(bif("0"), amounts.consumptionTax)
    }

    @Test
    fun `a line without VAT definition has no VAT`() {
        val amounts = compute(line("500", "10", taxes = emptyMap()))

        assertObr(amounts, nvat = "5000", vat = "0", wvat = "5000", total = "5000")
    }

    @Test
    fun `a price including taxes gives back exactly the price times the quantity`() {
        val amounts = compute(line("590", "10", mode = PriceMode.INCLUDING_TAXES))

        assertEquals(bif("500"), amounts.unitPrice)
        assertObr(amounts, nvat = "5000", vat = "900", wvat = "5900", total = "5900")
    }

    @Test
    fun `the second example of the specification is found back from its total`() {
        val amounts = compute(
            line(
                "242.04", "10",
                taxes = mapOf(
                    TaxKind.VAT to vat18,
                    TaxKind.CONSUMPTION_TAX to TaxComputation.FreeAmount,
                    TaxKind.FLAT_RATE_WITHHOLDING to TaxComputation.FreeAmount,
                ),
                freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to "30", TaxKind.FLAT_RATE_WITHHOLDING to "25"),
                mode = PriceMode.INCLUDING_TAXES,
            ),
        )

        assertEquals(bif("200"), amounts.unitPrice)
        assertObr(amounts, nvat = "2030", vat = "365.4", wvat = "2395.4", total = "2420.4")
    }

    @Test
    fun `every tax mode and base works with a price including taxes`() {
        val taxSets = listOf(
            mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to percent("2", PercentageBase.PRICE)),
            mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to perUnit("3")),
            mapOf(TaxKind.VAT to vat18, TaxKind.OTT to percent("5", PercentageBase.PRICE)),
            mapOf(TaxKind.VAT to vat18, TaxKind.TSCE to perUnit("1.5")),
        ) + PercentageBase.entries.map { base ->
            mapOf(TaxKind.VAT to vat18, TaxKind.FLAT_RATE_WITHHOLDING to percent("1", base))
        } + listOf(
            mapOf(TaxKind.VAT to vat18, TaxKind.FLAT_RATE_WITHHOLDING to perUnit("2.5")),
        )

        for (taxes in taxSets) {
            val amounts = compute(line("1234.56", "3", taxes = taxes, mode = PriceMode.INCLUDING_TAXES))

            assertEquals(bif("3703.68"), amounts.total, "$taxes")
            assertConsistent(amounts)
        }
    }

    @Test
    fun `thousands of prices including taxes keep every sum exact after rounding`() {
        val taxes = mapOf(
            TaxKind.VAT to vat18,
            TaxKind.CONSUMPTION_TAX to percent("3.7", PercentageBase.PRICE),
            TaxKind.OTT to perUnit("0.35"),
            TaxKind.FLAT_RATE_WITHHOLDING to percent("1.25", PercentageBase.PRICE_WITH_VAT),
        )
        for (cents in 100..4_000 step 7) {
            for (quantity in listOf("1", "3", "2.5", "12")) {
                val price = BigDecimal(cents).movePointLeft(2).toPlainString()
                val amounts = compute(line(price, quantity, taxes = taxes, mode = PriceMode.INCLUDING_TAXES))

                assertEquals(
                    Money.of(BigDecimal(price) * BigDecimal(quantity), Currency.BIF).rounded(ObrRoundingPolicy),
                    amounts.total,
                    "$price x $quantity",
                )
                assertConsistent(amounts)
            }
        }
    }

    @Test
    fun `thousands of prices excluding taxes keep every sum exact after rounding`() {
        val taxes = mapOf(
            TaxKind.VAT to vat18,
            TaxKind.CONSUMPTION_TAX to percent("3.7", PercentageBase.PRICE),
            TaxKind.TSCE to perUnit("0.35"),
            TaxKind.FLAT_RATE_WITHHOLDING to percent("1.25", PercentageBase.PRICE_WITHOUT_VAT),
        )
        for (cents in 1..4_000 step 7) {
            val amounts = compute(line(BigDecimal(cents).movePointLeft(2).toPlainString(), "3", taxes = taxes))

            assertConsistent(amounts)
        }
    }

    @Test
    fun `fixed taxes larger than a price including taxes are refused`() {
        assertFailsWith<IllegalArgumentException> {
            compute(
                line(
                    "1", "1",
                    taxes = mapOf(TaxKind.VAT to vat18, TaxKind.CONSUMPTION_TAX to perUnit("5")),
                    mode = PriceMode.INCLUDING_TAXES,
                ),
            )
        }
    }

    @Test
    fun `the line keeps the currency of its price`() {
        val input = line("10", "2").copy(unitPrice = Money.of("10", Currency.USD))

        val amounts = compute(input)

        assertEquals(Money.of("23.6", Currency.USD), amounts.total)
    }

    @Test
    fun `a free amount in another currency is refused`() {
        val input = line("10", "2", taxes = mapOf(TaxKind.CONSUMPTION_TAX to TaxComputation.FreeAmount))
            .copy(freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to Money.of("1", Currency.EUR)))

        assertFailsWith<IllegalArgumentException> { compute(input) }
    }

    @Test
    fun `the totals of an invoice add up its lines`() {
        val lines = listOf(compute(line("500", "10")), compute(line("200", "10")), compute(line("900", "10")))

        val totals = InvoiceTotals.of(lines)

        assertEquals(bif("16000"), totals.priceWithoutVat)
        assertEquals(bif("2880"), totals.vat)
        assertEquals(bif("18880"), totals.priceWithVat)
        assertEquals(bif("18880"), totals.total)
        assertEquals(bif("0"), totals.flatRateWithholding)
    }

    @Test
    fun `the totals of an empty invoice are zero in its currency`() {
        assertEquals(Money.zero(Currency.USD), InvoiceTotals.of(emptyList(), Currency.USD).total)
    }
}
