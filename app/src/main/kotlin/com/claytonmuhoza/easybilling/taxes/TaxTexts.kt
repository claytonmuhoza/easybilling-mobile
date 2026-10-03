package com.claytonmuhoza.easybilling.taxes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxProblem
import com.claytonmuhoza.easybilling.ui.decimalText

/** @return the short name of the tax: `TVA`, `TC`… */
@Composable
fun TaxKind.shortLabel(): String = stringResource(
    when (this) {
        TaxKind.VAT -> R.string.tax_kind_vat
        TaxKind.CONSUMPTION_TAX -> R.string.tax_kind_ct
        TaxKind.FLAT_RATE_WITHHOLDING -> R.string.tax_kind_pfl
        TaxKind.OTT -> R.string.tax_kind_ott
        TaxKind.TSCE -> R.string.tax_kind_tsce
    },
)

/** @return the full name of the tax. */
@Composable
fun TaxKind.longLabel(): String = stringResource(
    when (this) {
        TaxKind.VAT -> R.string.tax_kind_vat_long
        TaxKind.CONSUMPTION_TAX -> R.string.tax_kind_ct_long
        TaxKind.FLAT_RATE_WITHHOLDING -> R.string.tax_kind_pfl_long
        TaxKind.OTT -> R.string.tax_kind_ott_long
        TaxKind.TSCE -> R.string.tax_kind_tsce_long
    },
)

/** @return the name of the way of computing. */
@Composable
fun TaxMode.label(): String = stringResource(
    when (this) {
        TaxMode.PERCENTAGE -> R.string.tax_mode_percentage
        TaxMode.PER_UNIT -> R.string.tax_mode_per_unit
        TaxMode.FREE_AMOUNT -> R.string.tax_mode_free
    },
)

/** @return the short name of the base of a percentage. */
@Composable
fun PercentageBase.shortLabel(): String = stringResource(
    when (this) {
        PercentageBase.PRICE -> R.string.tax_base_price_short
        PercentageBase.PRICE_WITHOUT_VAT -> R.string.tax_base_price_without_vat_short
        PercentageBase.PRICE_WITH_VAT -> R.string.tax_base_price_with_vat_short
    },
)

/**
 * @param kind the tax the rate belongs to: only the PFL shows the base of its percentage.
 * @return the rate in words: `18 %`, `1 % of price with VAT`, `3 per unit`.
 */
@Composable
fun TaxComputation.describe(kind: TaxKind): String = when (this) {
    is TaxComputation.Percentage -> if (kind == TaxKind.FLAT_RATE_WITHHOLDING) {
        stringResource(R.string.tax_rate_percentage_of, decimalText(rate), base.longLabel())
    } else {
        stringResource(R.string.tax_rate_percentage, decimalText(rate))
    }
    is TaxComputation.PerUnit -> stringResource(R.string.tax_rate_per_unit, decimalText(amount))
    TaxComputation.FreeAmount -> stringResource(R.string.tax_rate_free)
}

@Composable
private fun PercentageBase.longLabel(): String = stringResource(
    when (this) {
        PercentageBase.PRICE -> R.string.tax_base_price
        PercentageBase.PRICE_WITHOUT_VAT -> R.string.tax_base_price_without_vat
        PercentageBase.PRICE_WITH_VAT -> R.string.tax_base_price_with_vat
    },
)

/** @return the message explaining the broken rule. */
@Composable
fun TaxProblem.message(): String = stringResource(
    when (this) {
        TaxProblem.NAME_MISSING -> R.string.tax_problem_name_missing
        TaxProblem.NAME_TOO_LONG -> R.string.tax_problem_name_too_long
        TaxProblem.KIND_MISSING -> R.string.tax_problem_kind_missing
        TaxProblem.MODE_MISSING -> R.string.tax_problem_mode_missing
        TaxProblem.MODE_NOT_ALLOWED -> R.string.tax_problem_mode_not_allowed
        TaxProblem.VALUE_MISSING -> R.string.tax_problem_value_missing
        TaxProblem.VALUE_INVALID -> R.string.tax_problem_value_invalid
        TaxProblem.RATE_ABOVE_100 -> R.string.tax_problem_rate_above_100
        TaxProblem.BASE_MISSING -> R.string.tax_problem_base_missing
        TaxProblem.DATE_MISSING -> R.string.tax_problem_date_missing
        TaxProblem.DATE_IN_PAST -> R.string.tax_problem_date_in_past
        TaxProblem.DATE_NOT_AFTER_LATEST -> R.string.tax_problem_date_not_after_latest
    },
)

/** The problems shown under each field of a tax. */
internal object TaxProblemFields {
    /** Shown under the kind. */
    val kind = setOf(TaxProblem.KIND_MISSING)

    /** Shown under the name. */
    val name = setOf(TaxProblem.NAME_MISSING, TaxProblem.NAME_TOO_LONG)

    /** Shown under the way of computing. */
    val mode = setOf(TaxProblem.MODE_MISSING, TaxProblem.MODE_NOT_ALLOWED)

    /** Shown under the value. */
    val value = setOf(TaxProblem.VALUE_MISSING, TaxProblem.VALUE_INVALID, TaxProblem.RATE_ABOVE_100)

    /** Shown under the base. */
    val base = setOf(TaxProblem.BASE_MISSING)

    /** Shown under the first day. */
    val date = setOf(TaxProblem.DATE_MISSING, TaxProblem.DATE_IN_PAST, TaxProblem.DATE_NOT_AFTER_LATEST)
}

/**
 * @param problems the broken rules of a form.
 * @param field the problems shown under one field.
 * @return the message of the first problem of that field, or null.
 */
@Composable
internal fun problemText(problems: Set<TaxProblem>, field: Set<TaxProblem>): String? =
    problems.firstOrNull { it in field }?.message()
