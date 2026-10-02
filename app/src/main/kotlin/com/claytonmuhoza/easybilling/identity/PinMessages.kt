package com.claytonmuhoza.easybilling.identity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.identity.PinProblem

/** @return the message shown to the user for a PIN that breaks a rule. */
@Composable
fun PinProblem.message(): String = stringResource(
    when (this) {
        PinProblem.NOT_DIGITS -> R.string.error_pin_not_digits
        PinProblem.TOO_SHORT -> R.string.error_pin_too_short
        PinProblem.TOO_LONG -> R.string.error_pin_too_long
        PinProblem.MISMATCH -> R.string.error_pin_mismatch
    },
)
