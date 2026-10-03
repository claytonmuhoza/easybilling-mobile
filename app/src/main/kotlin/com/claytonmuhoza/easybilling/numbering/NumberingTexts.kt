package com.claytonmuhoza.easybilling.numbering

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingProblem
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType

/** @return the name of the type of document, with its code: `Credit note (FA)`. */
@Composable
fun DocumentType.label(): String = stringResource(
    when (this) {
        DocumentType.FN -> R.string.numbering_type_fn
        DocumentType.FA -> R.string.numbering_type_fa
        DocumentType.RC -> R.string.numbering_type_rc
        DocumentType.RHF -> R.string.numbering_type_rhf
    },
)

/** @return when the counters start again, in words. */
@Composable
fun CounterReset.label(): String = stringResource(
    when (this) {
        CounterReset.YEARLY -> R.string.numbering_reset_yearly
        CounterReset.NEVER -> R.string.numbering_reset_never
    },
)

/** @return how the year is written, in words. */
@Composable
fun YearFormat.label(): String = stringResource(
    when (this) {
        YearFormat.NONE -> R.string.numbering_year_none
        YearFormat.SHORT -> R.string.numbering_year_short
        YearFormat.FULL -> R.string.numbering_year_full
    },
)

/** @return the separator as shown on its button; "None" for the empty one. */
@Composable
internal fun separatorLabel(separator: String): String =
    separator.ifEmpty { stringResource(R.string.numbering_separator_none) }

/** @return the message explaining the broken rule. */
@Composable
fun NumberingProblem.message(): String = stringResource(
    when (this) {
        NumberingProblem.DEVICE_CODE_INVALID -> R.string.numbering_problem_device_code
        NumberingProblem.INVALID_CHARACTER, NumberingProblem.UNKNOWN_TOKEN -> R.string.numbering_problem_prefix
        NumberingProblem.YEAR_MISSING -> R.string.numbering_problem_year_missing
        NumberingProblem.TOO_LONG -> R.string.numbering_problem_too_long
        NumberingProblem.IDENTIFIER_TOO_LONG -> R.string.numbering_problem_identifier_too_long
        // The fields of the screen cannot break these rules.
        NumberingProblem.TYPE_MISSING,
        NumberingProblem.COUNTER_MISSING,
        NumberingProblem.COUNTER_DIGITS,
        NumberingProblem.DEVICE_MISSING,
        NumberingProblem.TOKEN_REPEATED,
        -> R.string.numbering_problem_other
    },
)

/** The problems shown under each field of the numbering; the others are shown above the button. */
internal object NumberingProblemFields {
    /** Shown under the device code. */
    val deviceCode = setOf(NumberingProblem.DEVICE_CODE_INVALID)

    /** Shown under the prefix. */
    val prefix = setOf(NumberingProblem.INVALID_CHARACTER, NumberingProblem.UNKNOWN_TOKEN)

    /** Shown under the choice of the reset. */
    val reset = setOf(NumberingProblem.YEAR_MISSING)

    /** Shown under a field. */
    val all = deviceCode + prefix + reset
}

/**
 * @param problems the broken rules of the numbering.
 * @param field the problems shown under one field.
 * @return the message of the first problem of that field, or null.
 */
@Composable
internal fun problemText(problems: Set<NumberingProblem>, field: Set<NumberingProblem>): String? =
    problems.firstOrNull { it in field }?.message()
