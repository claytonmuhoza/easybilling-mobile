package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What is typed for a line of a sale: quantity, discount (UC-C03) and free tax amounts. */
class SaleLineFormTest {

    private fun valid(form: SaleLineForm) = assertIs<SaleLineCheck.Valid>(form.check())

    private fun problems(form: SaleLineForm) = assertIs<SaleLineCheck.Invalid>(form.check()).problems

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    @Test
    fun `a line starts with a quantity of one, without discount`() {
        val line = valid(SaleLineForm())

        assertEquals(Quantity.of("1"), line.quantity)
        assertNull(line.discount)
        assertEquals(emptyMap(), line.freeAmounts)
    }

    @Test
    fun `a quantity has two decimals at most, with a comma or a dot`() {
        assertEquals(Quantity.of("1.5"), valid(SaleLineForm(quantity = " 1,5 ")).quantity)
        assertEquals(Quantity.of("0.25"), valid(SaleLineForm(quantity = "0.25")).quantity)
        assertEquals(Quantity.of("12"), valid(SaleLineForm(quantity = "12.00")).quantity)

        for (refused in listOf("", "0", "-1", "1.255", "abc", "1e3x")) {
            assertEquals(setOf(SaleLineProblem.QUANTITY_INVALID), problems(SaleLineForm(quantity = refused)), refused)
        }
    }

    @Test
    fun `a discount is a percentage below 100 or an amount per unit`() {
        val percent = valid(SaleLineForm(discountKind = DiscountKind.PERCENT, discountValue = "12,5"))
        assertEquals(Discount.Percent(BigDecimal("12.5")), percent.discount)

        val amount = valid(SaleLineForm(discountKind = DiscountKind.PER_UNIT, discountValue = "250"))
        assertEquals(Discount.PerUnit(BigDecimal("250")), amount.discount)
        // An amount is not limited to 100.
        assertEquals(Discount.PerUnit(BigDecimal("100")), valid(amount.form("100")).discount)

        for (refused in listOf("", "0", "100", "150", "-5", "1.234", "x")) {
            val form = SaleLineForm(discountKind = DiscountKind.PERCENT, discountValue = refused)
            assertEquals(setOf(SaleLineProblem.DISCOUNT_INVALID), problems(form), refused)
        }
        for (refused in listOf("", "0", "-5", "1.234")) {
            val form = SaleLineForm(discountKind = DiscountKind.PER_UNIT, discountValue = refused)
            assertEquals(setOf(SaleLineProblem.DISCOUNT_INVALID), problems(form), refused)
        }
    }

    private fun SaleLineCheck.Valid.form(value: String) =
        SaleLineForm(quantity.toPlainString(), DiscountKind.PER_UNIT, value)

    @Test
    fun `a value typed without a kind of discount is ignored`() {
        assertNull(valid(SaleLineForm(discountValue = "10")).discount)
    }

    @Test
    fun `a discount lowers the unit price, rounded to two decimals`() {
        val price = bif("1999")

        assertEquals(bif("1799.1"), Discount.Percent(BigDecimal("10")).applyTo(price, ObrRoundingPolicy))
        assertEquals(bif("1749.13"), Discount.Percent(BigDecimal("12.5")).applyTo(price, ObrRoundingPolicy))
        assertEquals(bif("1499"), Discount.PerUnit(BigDecimal("500")).applyTo(price, ObrRoundingPolicy))
        assertEquals(bif("-1"), Discount.PerUnit(BigDecimal("2000")).applyTo(price, ObrRoundingPolicy))
        assertEquals(Discount.Percent(BigDecimal("10")), Discount.Percent(BigDecimal("10.0")))
        assertEquals(Discount.PerUnit(BigDecimal("5")), Discount.PerUnit(BigDecimal("5.00")))
    }

    @Test
    fun `free tax amounts are optional, never negative and limited to two decimals`() {
        val typed = mapOf(TaxKind.CONSUMPTION_TAX to "30,5", TaxKind.OTT to " ", TaxKind.TSCE to "0")
        val form = SaleLineForm(freeAmounts = typed)

        assertEquals(
            mapOf(TaxKind.CONSUMPTION_TAX to bif("30.5"), TaxKind.TSCE to bif("0")),
            valid(form).freeAmounts,
        )
        for (refused in listOf("-1", "1.234", "x")) {
            val wrong = SaleLineForm(freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to refused))
            assertEquals(setOf(SaleLineProblem.FREE_AMOUNT_INVALID), problems(wrong), refused)
        }
    }

    @Test
    fun `every problem is reported at once`() {
        val form = SaleLineForm("0", DiscountKind.PERCENT, "100", mapOf(TaxKind.OTT to "x"))

        assertEquals(SaleLineProblem.entries.toSet(), problems(form))
    }

    @Test
    fun `a stored line gives back its form`() {
        val line = DraftLine(
            itemId = ItemId(1),
            quantity = Quantity.of("2.5"),
            discount = Discount.Percent(BigDecimal("10.0")),
            freeAmounts = mapOf(TaxKind.CONSUMPTION_TAX to bif("30")),
        )

        assertEquals(
            SaleLineForm("2.5", DiscountKind.PERCENT, "10", mapOf(TaxKind.CONSUMPTION_TAX to "30")),
            SaleLineForm.from(line),
        )
        val amount = line.copy(discount = Discount.PerUnit(BigDecimal("250")), freeAmounts = emptyMap())
        assertEquals(SaleLineForm("2.5", DiscountKind.PER_UNIT, "250"), SaleLineForm.from(amount))
        assertEquals(SaleLineForm("2.5"), SaleLineForm.from(amount.copy(discount = null)))
    }
}
