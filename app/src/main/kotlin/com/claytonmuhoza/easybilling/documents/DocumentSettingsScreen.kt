package com.claytonmuhoza.easybilling.documents

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings

/** Tallest a logo is shown on the screen. */
private val LogoPreviewHeight = 96.dp

/**
 * Settings of the documents wired to their view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the settings state.
 */
@Composable
fun DocumentSettingsRoute(onBack: () -> Unit, viewModel: DocumentSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.onLogoPicked { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        }
    }
    DocumentSettingsScreen(
        state = state,
        actions = DocumentSettingsActions(
            onBack = onBack,
            onFooterChange = viewModel::onFooterChange,
            onQrCodeChange = viewModel::onQrCodeChange,
            onChooseLogo = { picker.launch("image/*") },
            onRemoveLogo = viewModel::onLogoRemoved,
            onSave = viewModel::save,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the settings of the documents.
 *
 * @property onBack called when the user goes back.
 * @property onFooterChange called with the footer as typed.
 * @property onQrCodeChange called when the user turns the QR code on or off.
 * @property onChooseLogo called when the user asks to choose an image.
 * @property onRemoveLogo called when the user takes the logo off.
 * @property onSave called when the user saves.
 * @property onRetry called when the user asks to read the settings again.
 */
class DocumentSettingsActions(
    val onBack: () -> Unit = {},
    val onFooterChange: (String) -> Unit = {},
    val onQrCodeChange: (Boolean) -> Unit = {},
    val onChooseLogo: () -> Unit = {},
    val onRemoveLogo: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * What the user chooses of the documents (UC-A08): a logo, a footer, and whether a QR code of the
 * identifier is printed. The mandatory mentions are not here: they cannot be changed.
 *
 * @param state the settings state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun DocumentSettingsScreen(
    state: DocumentSettingsUiState,
    actions: DocumentSettingsActions,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.document_settings_title)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            DocumentSettingsUiState.Loading -> LoadingState()
            DocumentSettingsUiState.Failed -> ErrorState(
                message = stringResource(R.string.document_settings_load_error),
                onRetry = actions.onRetry,
            )
            is DocumentSettingsUiState.Ready -> FormColumn {
                Text(
                    text = stringResource(R.string.document_settings_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LogoSection(state, actions)
                Fields(state, actions)
                Button(onClick = actions.onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.document_settings_save))
                }
                if (state.saved) {
                    StatusBanner(StatusTone.SUCCESS, stringResource(R.string.document_settings_saved))
                }
                if (state.problem == DocumentSettingsProblem.NOT_SAVED) {
                    StatusBanner(StatusTone.ERROR, stringResource(R.string.document_settings_not_saved))
                }
            }
        }
    }
}

/** The logo as it will be printed, and the ways to choose another or to take it off. */
@Composable
private fun LogoSection(state: DocumentSettingsUiState.Ready, actions: DocumentSettingsActions) {
    SectionHeader(stringResource(R.string.document_logo))
    val logo = state.settings.logo
    if (logo == null) {
        Text(stringResource(R.string.document_logo_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LogoPreview(logo)
    }
    if (state.problem == DocumentSettingsProblem.NOT_AN_IMAGE) {
        Text(stringResource(R.string.document_logo_not_an_image), color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        OutlinedButton(onClick = actions.onChooseLogo, enabled = !state.busy) {
            Text(stringResource(R.string.document_logo_choose))
        }
        if (logo != null) {
            OutlinedButton(onClick = actions.onRemoveLogo, enabled = !state.busy) {
                Text(stringResource(R.string.document_logo_remove))
            }
        }
    }
}

@Composable
private fun LogoPreview(logo: DocumentLogo) {
    val image = remember(logo) { BitmapFactory.decodeByteArray(logo.bytes, 0, logo.bytes.size)?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.document_logo_description),
            modifier = Modifier.heightIn(max = LogoPreviewHeight),
        )
    }
}

/** The footer and the QR code. */
@Composable
private fun Fields(state: DocumentSettingsUiState.Ready, actions: DocumentSettingsActions) {
    SectionHeader(stringResource(R.string.document_bottom))
    CountedTextField(
        value = state.settings.footer,
        onValueChange = actions.onFooterChange,
        label = stringResource(R.string.document_footer),
        maxLength = DocumentSettings.MAX_FOOTER_LENGTH,
        errorMessage = stringResource(R.string.document_footer_too_long)
            .takeIf { state.problem == DocumentSettingsProblem.FOOTER_TOO_LONG },
        hint = stringResource(R.string.document_footer_hint),
    )
    SwitchRow(
        label = stringResource(R.string.document_qr_code),
        checked = state.settings.showQrCode,
        onCheckedChange = actions.onQrCodeChange,
    )
    Text(
        text = stringResource(R.string.document_qr_code_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
