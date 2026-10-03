package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal

/**
 * A quantity of an item, exact and strictly positive. Useless zeros are dropped, so `10` and
 * `10.000` are the same quantity.
 *
 * @property value the quantity.
 */
class Quantity private constructor(val value: BigDecimal) {

    /** @return the quantity as plain text, without useless zero nor exponent: `2.5`, `10`. */
    fun toPlainString(): String = value.toPlainString()

    override fun equals(other: Any?): Boolean = other is Quantity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = toPlainString()

    /** Ways to create a quantity. */
    companion object {
        /**
         * @param value the quantity.
         * @return the quantity.
         * @throws IllegalArgumentException if [value] is zero or negative.
         */
        fun of(value: BigDecimal): Quantity {
            require(value.signum() > 0) { "A quantity is strictly positive, not $value" }
            return Quantity(value.stripTrailingZeros())
        }

        /**
         * @param value the quantity as decimal text, with a dot: `2.5`.
         * @return the quantity.
         * @throws NumberFormatException if [value] is not a decimal number.
         * @throws IllegalArgumentException if [value] is zero or negative.
         */
        fun of(value: String): Quantity = of(BigDecimal(value))
    }
}
