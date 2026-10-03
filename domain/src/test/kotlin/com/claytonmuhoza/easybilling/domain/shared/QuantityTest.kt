package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantityTest {

    @Test
    fun `a quantity is read from its decimal text`() {
        assertEquals(BigDecimal("2.5"), Quantity.of("2.5").value)
    }

    @Test
    fun `useless zeros do not make two quantities different`() {
        assertEquals(Quantity.of("10"), Quantity.of("10.000"))
        assertEquals(Quantity.of("10").hashCode(), Quantity.of("10.000").hashCode())
        assertEquals("10", Quantity.of("10.000").toPlainString())
    }

    @Test
    fun `a quantity is strictly positive`() {
        assertFailsWith<IllegalArgumentException> { Quantity.of("0") }
        assertFailsWith<IllegalArgumentException> { Quantity.of("-1") }
    }

    @Test
    fun `a text that is not a number is refused`() {
        assertFailsWith<NumberFormatException> { Quantity.of("two") }
    }
}
