package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers

/** Narrowest room kept for the quantity between the two buttons of a [QuantityStepper]. */
private val QuantityMinWidth = 32.dp

/**
 * A quantity between a button that takes one off and a button that adds one.
 *
 * @param quantity the quantity, already written for the language of the device.
 * @param onLess called when the user takes one off.
 * @param onMore called when the user adds one.
 * @param lessDescription what the first button does, read by the screen readers.
 * @param moreDescription what the second button does, read by the screen readers.
 * @param modifier modifier applied to the stepper.
 * @param enabled whether the two buttons can be pressed.
 */
@Composable
fun QuantityStepper(
    quantity: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessDescription: String,
    moreDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLess, enabled = enabled) {
                AppIconImage(AppIcon.REMOVE, lessDescription)
            }
            Text(
                text = quantity,
                modifier = Modifier.widthIn(min = QuantityMinWidth),
                style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onMore, enabled = enabled) {
                AppIconImage(AppIcon.ADD, moreDescription, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * A search field shaped like a pill, with a magnifying glass. The label stays readable once
 * something is typed.
 *
 * @param query what is typed.
 * @param onQueryChange called with the new content of the field.
 * @param label what the search looks for.
 * @param modifier modifier applied to the field.
 * @param onSubmit called when the search is submitted, by the keyboard or by a barcode reader.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { AppIconImage(AppIcon.SEARCH, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        singleLine = true,
        shape = CircleShape,
    )
}
