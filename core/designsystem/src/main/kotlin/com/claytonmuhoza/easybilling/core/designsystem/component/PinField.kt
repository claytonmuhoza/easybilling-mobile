package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Field for a numeric secret (PIN, manager code): masked, digits only, numeric keyboard.
 *
 * @param value the digits typed so far.
 * @param onValueChange called with the new digits; anything else typed is dropped.
 * @param label label of the field.
 * @param maxLength number of digits after which typing has no effect.
 * @param modifier modifier applied to the field.
 * @param errorMessage error shown under the field, or null when the value is acceptable.
 * @param supportingText hint shown under the field when there is no error, or null.
 * @param enabled whether the field accepts input.
 * @param imeAction action of the keyboard's action key.
 * @param onImeAction called when the user presses the keyboard's action key.
 */
@Composable
fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val message = errorMessage ?: supportingText
    val supportingColor = if (errorMessage != null) {
        MaterialTheme.colorScheme.error
    } else {
        OutlinedTextFieldDefaults.colors().disabledSupportingTextColor
    }
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onValueChange(typed.filter { it in '0'..'9' }.take(maxLength)) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        supportingText = message?.let { { Text(it) } },
        isError = errorMessage != null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        singleLine = true,
        // A disabled field can still carry an error (locked account): it must stay readable.
        colors = OutlinedTextFieldDefaults.colors(disabledSupportingTextColor = supportingColor),
    )
}
