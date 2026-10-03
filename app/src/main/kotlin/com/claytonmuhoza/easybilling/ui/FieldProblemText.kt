package com.claytonmuhoza.easybilling.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.FieldProblem

/** @return the message explaining why the value of a field is refused. */
@Composable
fun FieldProblem.message(): String = when (this) {
    FieldProblem.Missing -> stringResource(R.string.tax_profile_required)
    is FieldProblem.TooLong -> pluralStringResource(R.plurals.tax_profile_too_long, maxLength, maxLength)
}
