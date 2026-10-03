package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MoneyTest {

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    @Test
    fun `an amount is read from its decimal text, never from a binary floating number`() {
        assertEquals(BigDecimal("0.1"), bif("0.1").amount)
        assertEquals(BigDecimal("365.4"), bif("365.4").amount)
    }

    @Test
    fun `useless zeros do not make two amounts different`() {
        assertEquals(bif("365.4"), bif("365.40"))
        assertEquals(bif("2030"), bif("2030.00"))
        assertEquals(bif("365.4").hashCode(), bif("365.40").hashCode())
    }

    @Test
    fun `the plain text of an amount has no useless zero and no exponent`() {
        assertEquals("365.4", bif("365.40").toPlainString())
        assertEquals("2030", bif("2030.00").toPlainString())
        assertEquals("0", bif("0.00").toPlainString())
    }

    @Test
    fun `amounts in different currencies are different`() {
        assertNotEquals(Money.of("10", Currency.BIF), Money.of("10", Currency.USD))
    }

    @Test
    fun `amounts are added and subtracted exactly`() {
        assertEquals(bif("0.3"), bif("0.1") + bif("0.2"))
        assertEquals(bif("2030"), bif("2000") + bif("30"))
        assertEquals(bif("1.1"), bif("2.3") - bif("1.2"))
    }

    @Test
    fun `a negative result is allowed`() {
        assertEquals(bif("-5"), bif("10") - bif("15"))
    }

    @Test
    fun `an amount is multiplied by a quantity exactly`() {
        assertEquals(bif("2000"), bif("200") * Quantity.of("10"))
        assertEquals(bif("0.75"), bif("0.3") * Quantity.of("2.5"))
    }

    @Test
    fun `amounts in two currencies cannot be added or subtracted`() {
        assertFailsWith<IllegalArgumentException> { bif("1") + Money.of("1", Currency.USD) }
        assertFailsWith<IllegalArgumentException> { bif("1") - Money.of("1", Currency.EUR) }
    }

    @Test
    fun `a rounded amount follows the rounding policy`() {
        assertEquals(bif("1042.02"), bif("1042.02").rounded(ObrRoundingPolicy))
        assertEquals(bif("0.01"), bif("0.005").rounded(ObrRoundingPolicy))
    }

    @Test
    fun `zero exists in every currency`() {
        assertEquals(Money.of("0", Currency.EUR), Money.zero(Currency.EUR))
    }

    @Test
    fun `a text that is not a number is refused`() {
        assertFailsWith<NumberFormatException> { bif("12,5") }
        assertFailsWith<NumberFormatException> { bif("") }
    }

    @Test
    fun `the text of an amount shows its currency`() {
        assertEquals("2395.4 BIF", bif("2395.40").toString())
    }
}
