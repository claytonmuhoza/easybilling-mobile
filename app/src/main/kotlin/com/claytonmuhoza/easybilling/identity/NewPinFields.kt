package com.claytonmuhoza.easybilling.identity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.PinField
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinProblem

/**
 * The two fields used wherever a PIN is chosen: the PIN and its confirmation. A mismatch is shown
 * under the confirmation, any other problem under the PIN.
 *
 * @param pinLabel label of the first field.
 * @param pin the PIN typed so far.
 * @param onPinChange called when the PIN changes.
 * @param confirmation the confirmation typed so far.
 * @param onConfirmationChange called when the confirmation changes.
 * @param problem the rule broken by the last submitted PIN, or null.
 * @param enabled whether the fields accept input.
 * @param onDone called when the user presses the keyboard's action key on the confirmation.
 */
@Composable
fun NewPinFields(
    pinLabel: String,
    pin: String,
    onPinChange: (String) -> Unit,
    confirmation: String,
    onConfirmationChange: (String) -> Unit,
    problem: PinProblem?,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val mismatch = problem == PinProblem.MISMATCH
    PinField(
        value = pin,
        onValueChange = onPinChange,
        label = pinLabel,
        maxLength = Pin.MAX_LENGTH,
        errorMessage = problem?.takeUnless { mismatch }?.message(),
        supportingText = stringResource(R.string.pin_rule_hint),
        enabled = enabled,
    )
    PinField(
        value = confirmation,
        onValueChange = onConfirmationChange,
        label = stringResource(R.string.field_pin_confirmation),
        maxLength = Pin.MAX_LENGTH,
        errorMessage = problem?.takeIf { mismatch }?.message(),
        enabled = enabled,
        imeAction = ImeAction.Done,
        onImeAction = onDone,
    )
}
