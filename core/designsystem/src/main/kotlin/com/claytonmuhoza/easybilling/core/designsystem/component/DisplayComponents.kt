package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.core.designsystem.theme.palette
import com.claytonmuhoza.easybilling.core.designsystem.theme.tabularNumbers

/** Size of the icon of a [StatusChip]. */
private val ChipIconSize = 18.dp

/** Size of the currency next to an amount, relative to the amount. */
private const val CURRENCY_SCALE = 0.7

/**
 * A status: its colour, its icon and its words together, so it is never told by the colour alone.
 *
 * @param tone what the status says.
 * @param label the status in words.
 * @param modifier modifier applied to the chip.
 */
@Composable
fun StatusChip(tone: StatusTone, label: String, modifier: Modifier = Modifier) {
    val palette = tone.palette()
    Row(
        modifier = modifier
            .background(palette.container, MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        AppIconImage(tone.icon(), contentDescription = null, Modifier.size(ChipIconSize), palette.content)
        Text(
            text = label,
            color = palette.content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A status said in a sentence, across the width of its container: its colour, its icon and its
 * words together.
 *
 * @param tone what the status says.
 * @param text the sentence.
 * @param modifier modifier applied to the banner.
 */
@Composable
fun StatusBanner(tone: StatusTone, text: String, modifier: Modifier = Modifier) {
    val palette = tone.palette()
    TonalCard(modifier = modifier.fillMaxWidth(), container = palette.container) {
        Row(
            modifier = Modifier.padding(Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            AppIconImage(tone.icon(), contentDescription = null, tint = palette.content)
            Text(text = text, color = palette.content)
        }
    }
}

/** @return the icon that goes with the colour of this status. */
fun StatusTone.icon(): AppIcon = when (this) {
    StatusTone.SUCCESS -> AppIcon.CHECK_CIRCLE
    StatusTone.PENDING -> AppIcon.SCHEDULE
    StatusTone.ERROR -> AppIcon.ERROR
    StatusTone.NEUTRAL -> AppIcon.BLOCK
}

/**
 * An amount of money: the number in tabular figures, so amounts line up in a column, and its
 * currency smaller next to it. Both are one text, read as one by the screen readers.
 *
 * @param amount the number, already written for the language of the device.
 * @param currency the code of the currency.
 * @param modifier modifier applied to the text.
 * @param style style of the number.
 * @param color colour of the text.
 * @param fontWeight weight of the text, or null for the one of [style].
 */
@Composable
fun AmountText(
    amount: String,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = buildAnnotatedString {
            append(amount)
            append(' ')
            withStyle(SpanStyle(fontSize = CURRENCY_SCALE.em)) { append(currency) }
        },
        modifier = modifier,
        style = style.tabularNumbers(),
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}

/**
 * A card drawn with a tone of the surface instead of an outline or a shadow. It is the container
 * of every group of related content.
 *
 * @param modifier modifier applied to the card.
 * @param onClick called when the card is pressed; the card is not clickable when null.
 * @param container colour of the card.
 * @param content the content of the card, laid out in a column.
 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val shape = MaterialTheme.shapes.large
    if (onClick == null) {
        Card(modifier = modifier, shape = shape, colors = colors, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, content = content)
    }
}

/**
 * A list item placed in a [TonalCard]: it takes the colour of the card instead of drawing its own.
 *
 * @param headlineContent the main text.
 * @param modifier modifier applied to the item.
 * @param overlineContent what is shown above the main text, or null.
 * @param supportingContent what is shown under the main text, or null.
 * @param leadingContent what is shown at the start, or null.
 * @param trailingContent what is shown at the end, or null.
 */
@Composable
fun CardListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = headlineContent,
        modifier = modifier,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * A row that opens something: an icon saying what it is about, a title, a line under it and,
 * at the end, whatever tells where it stands.
 *
 * @param title what the row opens.
 * @param onClick called when the row is pressed.
 * @param modifier modifier applied to the row.
 * @param supporting what is said under the title, or null.
 * @param icon the icon at the start of the row, or null.
 * @param trailing what is shown at the end of the row, or null.
 * @param container colour of the row; the main action of a screen takes a stronger one.
 */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: AppIcon? = null,
    trailing: (@Composable () -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    TonalCard(modifier = modifier.fillMaxWidth(), onClick = onClick, container = container) {
        CardListItem(
            headlineContent = { Text(title) },
            supportingContent = supporting?.let { { Text(it) } },
            leadingContent = icon?.let { { IconTile(it) } },
            trailingContent = trailing,
        )
    }
}
