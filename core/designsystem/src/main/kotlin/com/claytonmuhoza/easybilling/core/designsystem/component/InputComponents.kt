package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/** Smallest height of a touch target (Material accessibility guideline). */
private val MinTouchTarget = 48.dp

/**
 * Single-line text field with a character counter. Typing beyond [maxLength] is allowed, so a
 * pasted value is not silently cut: the counter turns to the error color and the form refuses it.
 *
 * @param value the text typed so far.
 * @param onValueChange called with the new text.
 * @param label label of the field.
 * @param maxLength longest accepted value, shown by the counter.
 * @param modifier modifier applied to the field.
 * @param errorMessage error shown under the field, or null when the value is acceptable.
 * @param hint text shown under the field when there is no error (for example "Optional"), or null.
 * @param keyboardType kind of keyboard to show.
 * @param capitalization automatic capitalization of the keyboard.
 */
@Composable
fun CountedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    // Counted after trimming, like the rules of the forms.
    val length = value.trim().length
    val counterColor = if (length > maxLength) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = errorMessage ?: hint.orEmpty(), modifier = Modifier.weight(1f))
                Text(
                    text = "$length / $maxLength",
                    color = counterColor,
                )
            }
        },
        isError = errorMessage != null,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )
}

/**
 * Choice of one option among a few, shown as segmented buttons.
 *
 * @param T type of the options.
 * @param label what is chosen.
 * @param options each option with its label, in display order.
 * @param selected the chosen option, or null when none is chosen yet.
 * @param onSelect called with the option the user picks.
 * @param modifier modifier applied to the field.
 * @param errorMessage error shown under the buttons, or null.
 */
@Composable
fun <T> ChoiceField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, optionLabel) ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(optionLabel)
                }
            }
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Spacing.medium),
            )
        }
    }
}

/**
 * A yes / no setting: a label and a switch. The whole row is one touch target.
 *
 * @param label what the switch turns on.
 * @param checked whether the setting is on.
 * @param onCheckedChange called with the new value.
 * @param modifier modifier applied to the row.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // The row handles the touch and announces the state: the switch itself is only drawn.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Title of a group of fields, announced as a heading by screen readers.
 *
 * @param text the title.
 * @param modifier modifier applied to the title.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(top = Spacing.small)
            .semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Text button of a top bar.
 *
 * @param label what the action does.
 * @param onClick called when the action is pressed.
 */
@Composable
fun RowScope.TextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
