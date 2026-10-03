package com.claytonmuhoza.easybilling.domain.configuration

import java.math.BigDecimal
import java.time.LocalDate

/** How the user chooses to compute a tax. */
enum class TaxMode {
    /** A percentage. */
    PERCENTAGE,

    /** An amount per unit sold. */
    PER_UNIT,

    /** An amount typed on each line. */
    FREE_AMOUNT,
}

/** A broken rule of a tax definition. */
enum class TaxProblem {
    /** The name is empty. */
    NAME_MISSING,

    /** The name is longer than [TaxForm.MAX_NAME_LENGTH]. */
    NAME_TOO_LONG,

    /** No kind of tax is chosen. */
    KIND_MISSING,

    /** No way of computing is chosen. */
    MODE_MISSING,

    /** The way of computing is not allowed for this kind (only a percentage for the VAT). */
    MODE_NOT_ALLOWED,

    /** No value is typed. */
    VALUE_MISSING,

    /** The value is not a number of zero or more with two decimals at most. */
    VALUE_INVALID,

    /** A percentage above 100. */
    RATE_ABOVE_100,

    /** A percentage of PFL without its base. */
    BASE_MISSING,

    /** No first day is chosen. */
    DATE_MISSING,

    /** The first day is before today: a rate never applies to the past. */
    DATE_IN_PAST,

    /** The first day is not after the first day of the latest rate. */
    DATE_NOT_AFTER_LATEST,
}

/** Result of [TaxRateForm.check]. */
sealed interface TaxRateCheck {
    /** Every rule holds. */
    data class Valid(val version: TaxVersion) : TaxRateCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<TaxProblem>) : TaxRateCheck
}

/**
 * A rate as typed by the user, not checked yet.
 *
 * @property mode the way of computing.
 * @property value the rate in percent or the amount per unit, as typed; a comma is a decimal point.
 * @property base what a percentage of PFL applies to; ignored for the other taxes.
 * @property validFrom the first day it applies.
 */
data class TaxRateForm(
    val mode: TaxMode? = null,
    val value: String = "",
    val base: PercentageBase? = null,
    val validFrom: LocalDate? = null,
) {
    /**
     * @param kind the kind of the tax.
     * @param today the current day of the business.
     * @param latest the first day of the latest rate of the tax, or null for a new tax.
     * @return the rate, or every broken rule.
     */
    fun check(kind: TaxKind, today: LocalDate, latest: LocalDate? = null): TaxRateCheck {
        val problems = mutableSetOf<TaxProblem>()
        val computation = computation(kind, problems)
        when {
            validFrom == null -> problems += TaxProblem.DATE_MISSING
            validFrom.isBefore(today) -> problems += TaxProblem.DATE_IN_PAST
            latest != null && !validFrom.isAfter(latest) -> problems += TaxProblem.DATE_NOT_AFTER_LATEST
        }
        return if (computation != null && validFrom != null && problems.isEmpty()) {
            TaxRateCheck.Valid(TaxVersion(validFrom, computation))
        } else {
            TaxRateCheck.Invalid(problems)
        }
    }

    private fun computation(kind: TaxKind, problems: MutableSet<TaxProblem>): TaxComputation? = when (mode) {
        null -> null.also { problems += TaxProblem.MODE_MISSING }
        TaxMode.FREE_AMOUNT, TaxMode.PER_UNIT -> if (kind == TaxKind.VAT) {
            null.also { problems += TaxProblem.MODE_NOT_ALLOWED }
        } else if (mode == TaxMode.FREE_AMOUNT) {
            TaxComputation.FreeAmount
        } else {
            amount(problems)?.let(TaxComputation::PerUnit)
        }
        TaxMode.PERCENTAGE -> percentage(kind, problems)
    }

    private fun percentage(kind: TaxKind, problems: MutableSet<TaxProblem>): TaxComputation? {
        val rate = amount(problems)
        if (rate != null && rate > HUNDRED) problems += TaxProblem.RATE_ABOVE_100
        val percentageBase = when (kind) {
            TaxKind.VAT -> PercentageBase.PRICE_WITHOUT_VAT
            TaxKind.FLAT_RATE_WITHHOLDING -> base ?: null.also { problems += TaxProblem.BASE_MISSING }
            else -> PercentageBase.PRICE
        }
        return if (rate != null && percentageBase != null && rate <= HUNDRED) {
            TaxComputation.Percentage(rate, percentageBase)
        } else {
            null
        }
    }

    /** Reads [value]: a number of zero or more, two decimals at most. */
    private fun amount(problems: MutableSet<TaxProblem>): BigDecimal? {
        val typed = value.trim().replace(',', '.')
        val number = typed.toBigDecimalOrNull()?.stripTrailingZeros()
        return when {
            typed.isEmpty() -> null.also { problems += TaxProblem.VALUE_MISSING }
            number == null || number.signum() < 0 || number.scale() > MAX_DECIMALS ->
                null.also { problems += TaxProblem.VALUE_INVALID }
            else -> number
        }
    }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
        const val MAX_DECIMALS = 2
    }
}

/** Result of [TaxForm.check]. */
sealed interface TaxCheck {
    /**
     * Every rule holds.
     *
     * @property kind the kind of the tax.
     * @property name the trimmed name.
     * @property version the first rate.
     */
    data class Valid(val kind: TaxKind, val name: String, val version: TaxVersion) : TaxCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<TaxProblem>) : TaxCheck
}

/**
 * A new tax as typed by the user, not checked yet.
 *
 * @property kind which tax of the OBR it fills.
 * @property name its name.
 * @property rate its first rate.
 */
data class TaxForm(
    val kind: TaxKind? = null,
    val name: String = "",
    val rate: TaxRateForm = TaxRateForm(),
) {
    /**
     * @param today the current day of the business.
     * @return the tax, or every broken rule.
     */
    fun check(today: LocalDate): TaxCheck {
        val problems = mutableSetOf<TaxProblem>()
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> problems += TaxProblem.NAME_MISSING
            trimmed.length > MAX_NAME_LENGTH -> problems += TaxProblem.NAME_TOO_LONG
        }
        if (kind == null) problems += TaxProblem.KIND_MISSING
        // Without a kind, the rate is checked with the rules every kind shares (those of the TC).
        val rateCheck = rate.check(kind ?: TaxKind.CONSUMPTION_TAX, today)
        if (rateCheck is TaxRateCheck.Invalid) problems += rateCheck.problems
        return if (kind != null && rateCheck is TaxRateCheck.Valid && problems.isEmpty()) {
            TaxCheck.Valid(kind, trimmed, rateCheck.version)
        } else {
            TaxCheck.Invalid(problems)
        }
    }

    /** Limits of the rule. */
    companion object {
        /** Longest name accepted (internal rule). */
        const val MAX_NAME_LENGTH = 50
    }
}
