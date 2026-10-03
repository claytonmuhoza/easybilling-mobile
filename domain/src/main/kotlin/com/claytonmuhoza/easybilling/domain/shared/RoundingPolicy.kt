package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal
import java.math.RoundingMode

/** How an exact amount is turned into the amount that is stored, printed and sent. */
fun interface RoundingPolicy {
    /**
     * @param amount the exact amount.
     * @return the rounded amount.
     */
    fun round(amount: BigDecimal): BigDecimal
}

/**
 * Rounding of every amount sent to the OBR (decision D08): two decimals at most, to the nearest,
 * a half going away from zero.
 */
object ObrRoundingPolicy : RoundingPolicy {
    private const val DECIMALS = 2

    override fun round(amount: BigDecimal): BigDecimal = amount.setScale(DECIMALS, RoundingMode.HALF_UP)
}
