package com.claytonmuhoza.easybilling.identity

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import com.claytonmuhoza.easybilling.core.designsystem.component.PinEntry
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinProblem

/**
 * One step of the choice of a PIN, typed on the PIN pad: the PIN itself, or its confirmation.
 * Used wherever a PIN is chosen, inside the content of an entry screen.
 *
 * @param title what the user is asked to type.
 * @param pin the digits typed so far at this step.
 * @param onPinChange called when the digits change.
 * @param onSubmit called when the user submits the step.
 * @param submitDescription what submitting the step does, read by the screen readers.
 * @param hint the rule shown while nothing is wrong, or null.
 * @param problem the rule broken by what was last submitted at this step, or null.
 * @param enabled whether the pad accepts input.
 */
@Composable
fun ColumnScope.PinStep(
    title: String,
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    submitDescription: String,
    hint: String?,
    problem: PinProblem?,
    enabled: Boolean,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    PinEntry(
        pin = pin,
        onPinChange = onPinChange,
        maxLength = Pin.MAX_LENGTH,
        onSubmit = onSubmit,
        submitDescription = submitDescription,
        slots = Pin.MIN_LENGTH,
        message = problem?.message() ?: hint,
        isError = problem != null,
        enabled = enabled,
    )
}

/**
 * @param problem the rule broken by a chosen PIN.
 * @return whether the problem is said at the confirmation step rather than at the PIN step.
 */
fun isConfirmationProblem(problem: PinProblem): Boolean = problem == PinProblem.MISMATCH
