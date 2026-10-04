package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing

/** Width from which the two parts of an [EntryScreen] are shown side by side. */
private val TwoPaneMinWidth = 600.dp

/** Keeps a part of an [EntryScreen] readable instead of stretching it across a large screen. */
private val PartMaxWidth = 360.dp

/** Side of the [BrandMark] and of its icon. */
private val BrandMarkSize = 56.dp
private val BrandIconSize = 32.dp

/** Diameter of an [InitialsAvatar]. */
private val AvatarSize = 40.dp

/** How many words of a name give a letter to its initials. */
private const val INITIALS_WORDS = 2

/**
 * Screen of the entrance of the application (first launch, login, new PIN), without title bar:
 * a [header] that says where the user is and a [content] where the user types. The two are
 * stacked on a phone and side by side on a wide screen. The screen scrolls and stays above the
 * keyboard.
 *
 * @param modifier modifier applied to the screen.
 * @param onBack called when the user presses the back arrow; no arrow is shown when null.
 * @param header what identifies the screen: brand, title, explanation.
 * @param content the fields, the PIN pad and the buttons.
 */
@Composable
fun EntryScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            if (maxWidth >= TwoPaneMinWidth) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Pane(Modifier.weight(1f).fillMaxHeight()) { Part(Spacing.small, header) }
                    Pane(Modifier.weight(1f).fillMaxHeight()) { Part(Spacing.medium, content) }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.large, vertical = Spacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterVertically),
                ) {
                    Part(Spacing.small, header)
                    Part(Spacing.medium, content)
                }
            }
            if (onBack != null) BackButton(onBack)
        }
    }
}

/** One half of a wide [EntryScreen]: it scrolls on its own and centres what it holds. */
@Composable
private fun Pane(modifier: Modifier, part: @Composable () -> Unit) {
    Box(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        part()
    }
}

@Composable
private fun Part(gap: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = PartMaxWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * The mark of the application: its icon on a square of the primary colour. It is decorative: the
 * name next to it carries the meaning.
 *
 * @param modifier modifier applied to the mark.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BrandMarkSize)
            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(
            icon = AppIcon.INVOICE,
            contentDescription = null,
            modifier = Modifier.size(BrandIconSize),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * A person shown by the initials of their name on a disc. It is decorative: the name next to it
 * carries the meaning.
 *
 * @param name the name of the person.
 * @param modifier modifier applied to the avatar.
 */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(AvatarSize)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * @param name the name of a person.
 * @return the first letter of its first two words, in capitals, or `?` when it has none.
 */
internal fun initialsOf(name: String): String = name
    .split(' ')
    .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    .take(INITIALS_WORDS)
    .joinToString("") { it.uppercase() }
    .ifEmpty { "?" }
