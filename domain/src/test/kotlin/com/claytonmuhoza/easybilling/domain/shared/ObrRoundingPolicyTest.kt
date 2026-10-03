package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Decision D08: two decimals at most, rounded to the nearest, a half going up. */
class ObrRoundingPolicyTest {

    private fun round(amount: String): BigDecimal = ObrRoundingPolicy.round(BigDecimal(amount))

    private fun assertRounds(expected: String, amount: String) {
        assertEquals(0, BigDecimal(expected).compareTo(round(amount)), "$amount should round to $expected")
    }

    @Test
    fun `an amount with two decimals or fewer is unchanged`() {
        assertRounds("365.4", "365.4")
        assertRounds("1042.02", "1042.02")
        assertRounds("5000", "5000")
    }

    @Test
    fun `the third decimal decides`() {
        assertRounds("1.23", "1.234")
        assertRounds("1.24", "1.236")
    }

    @Test
    fun `a half goes up`() {
        assertRounds("0.01", "0.005")
        assertRounds("2.13", "2.125")
        assertRounds("2.12", "2.1249999")
    }

    @Test
    fun `a negative half goes away from zero, like its positive`() {
        assertRounds("-0.01", "-0.005")
        assertRounds("-2.13", "-2.125")
    }

    @Test
    fun `the result never has more than two decimals`() {
        assertEquals(2, round("1.23456789").scale())
    }
}
