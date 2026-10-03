package com.claytonmuhoza.easybilling.domain.shared

import java.math.BigDecimal

/**
 * An exact amount of money in a currency. It is never built from a binary floating number, and
 * useless zeros are dropped, so `365.4` and `365.40` are the same amount.
 *
 * Operations are exact; rounding only happens when [rounded] is called.
 *
 * @property amount the amount, possibly negative.
 * @property currency the currency of the amount.
 */
class Money private constructor(val amount: BigDecimal, val currency: Currency) {

    /**
     * @param other an amount in the same currency.
     * @return the sum.
     * @throws IllegalArgumentException if the currencies differ.
     */
    operator fun plus(other: Money): Money = of(amount + sameCurrency(other).amount, currency)

    /**
     * @param other an amount in the same currency.
     * @return the difference, possibly negative.
     * @throws IllegalArgumentException if the currencies differ.
     */
    operator fun minus(other: Money): Money = of(amount - sameCurrency(other).amount, currency)

    /**
     * @param quantity the number of units.
     * @return this amount multiplied by [quantity], exactly.
     */
    operator fun times(quantity: Quantity): Money = of(amount * quantity.value, currency)

    /**
     * @param policy the rounding to apply.
     * @return this amount rounded by [policy].
     */
    fun rounded(policy: RoundingPolicy): Money = of(policy.round(amount), currency)

    /** @return the amount as plain text, without useless zero nor exponent: `365.4`, `2030`. */
    fun toPlainString(): String = amount.toPlainString()

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Cannot combine $currency and ${other.currency}" }
        return other
    }

    override fun equals(other: Any?): Boolean =
        other is Money && amount == other.amount && currency == other.currency

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${toPlainString()} $currency"

    /** Ways to create an amount. */
    companion object {
        /**
         * @param amount the amount.
         * @param currency its currency.
         * @return the amount of money.
         */
        fun of(amount: BigDecimal, currency: Currency): Money = Money(amount.stripTrailingZeros(), currency)

        /**
         * @param amount the amount as decimal text, with a dot: `2395.4`.
         * @param currency its currency.
         * @return the amount of money.
         * @throws NumberFormatException if [amount] is not a decimal number.
         */
        fun of(amount: String, currency: Currency): Money = of(BigDecimal(amount), currency)

        /**
         * @param currency the currency.
         * @return zero in [currency].
         */
        fun zero(currency: Currency): Money = of(BigDecimal.ZERO, currency)
    }
}
