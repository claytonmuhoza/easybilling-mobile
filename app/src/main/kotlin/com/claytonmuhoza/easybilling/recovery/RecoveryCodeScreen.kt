package com.claytonmuhoza.easybilling.recovery

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.BrandMark
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import kotlinx.coroutines.launch

/**
 * Key that marks a clipboard content as sensitive: from Android 13 the system hides it in the
 * clipboard preview. It is the value of `ClipDescription.EXTRA_IS_SENSITIVE`, which older versions
 * do not define but ignore without harm.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Shows a recovery code, once, lets the user copy it and asks to confirm it was written down.
 *
 * @param code the recovery code, formatted in groups.
 * @param onAcknowledge called when the user confirms the code was written down.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun RecoveryCodeScreen(code: String, onAcknowledge: () -> Unit, modifier: Modifier = Modifier) {
    EntryScreen(
        modifier = modifier,
        header = {
            BrandMark()
            Text(
                text = stringResource(R.string.recovery_code_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.recovery_code_explanation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        TonalCard(
            modifier = Modifier.fillMaxWidth(),
            container = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.large),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        StatusBanner(StatusTone.PENDING, stringResource(R.string.recovery_code_once))
        CopyButton(code)
        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_code_acknowledge))
        }
    }
}

/** Copies [code] to the clipboard as a sensitive content, then says it is done. */
@Composable
private fun CopyButton(code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.field_recovery_code)
    var copied by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(sensitiveClip(label, code)))
                copied = true
            }
        },
        // The change of the label is announced: nothing else tells a blind user the copy is done.
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        AppIconImage(
            icon = if (copied) AppIcon.CHECK_CIRCLE else AppIcon.COPY,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(if (copied) R.string.recovery_code_copied else R.string.recovery_code_copy))
    }
}

/**
 * @param label what the content is, shown by the system next to it.
 * @param text the secret to copy.
 * @return a clipboard content that the system does not show in its preview.
 */
private fun sensitiveClip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
}
