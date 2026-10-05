package com.claytonmuhoza.easybilling.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import com.claytonmuhoza.easybilling.domain.document.DocumentSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.SaveDocumentSettings
import com.claytonmuhoza.easybilling.platform.document.LogoImage
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Why the settings of the documents are not as the user wants them. */
enum class DocumentSettingsProblem {
    /** The footer is longer than [DocumentSettings.MAX_FOOTER_LENGTH]. */
    FOOTER_TOO_LONG,

    /** The file chosen as a logo is not an image, or could not be read. */
    NOT_AN_IMAGE,

    /** The settings could not be written. */
    NOT_SAVED,
}

/** State of the settings of the documents. */
sealed interface DocumentSettingsUiState {
    /** The settings are being read. */
    data object Loading : DocumentSettingsUiState

    /** The settings could not be read. */
    data object Failed : DocumentSettingsUiState

    /**
     * The settings are shown.
     *
     * @property settings the footer, the logo and whether the QR code is printed, as typed.
     * @property problem what is wrong, or null.
     * @property saved true once the settings shown are the saved ones.
     * @property busy true while the settings are written or a logo is read.
     */
    data class Ready(
        val settings: DocumentSettings,
        val problem: DocumentSettingsProblem? = null,
        val saved: Boolean = false,
        val busy: Boolean = false,
    ) : DocumentSettingsUiState
}

/** Lets the user choose the logo, the footer and the QR code of the documents (UC-A08). */
@HiltViewModel
class DocumentSettingsViewModel @Inject constructor(
    private val repository: DocumentSettingsRepository,
    private val saveSettings: SaveDocumentSettings,
) : ViewModel() {

    private val mutableState = MutableStateFlow<DocumentSettingsUiState>(DocumentSettingsUiState.Loading)

    /** The state to show. */
    val state: StateFlow<DocumentSettingsUiState> = mutableState.asStateFlow()

    private val ready get() = mutableState.value as? DocumentSettingsUiState.Ready

    init {
        load()
    }

    /** Reads the saved settings. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { DocumentSettingsUiState.Ready(repository.read()) }
                .getOrElse { DocumentSettingsUiState.Failed }
        }
    }

    /** @param footer the footer as typed. */
    fun onFooterChange(footer: String) = change { it.copy(footer = footer) }

    /** @param show whether the QR code of the identifier is printed. */
    fun onQrCodeChange(show: Boolean) = change { it.copy(showQrCode = show) }

    /** Takes the logo off the documents. */
    fun onLogoRemoved() = change { it.copy(logo = null) }

    /**
     * Takes the image the user chose as the logo, reduced and stored as a PNG.
     *
     * @param read reads the chosen file; it runs off the main thread and gives null when the file
     * cannot be opened.
     */
    fun onLogoPicked(read: () -> ByteArray?) {
        val shown = ready ?: return
        mutableState.value = shown.copy(busy = true, problem = null, saved = false)
        viewModelScope.launch {
            val png = withContext(Dispatchers.IO) {
                try {
                    read()?.let { LogoImage.toPng(it) }
                } catch (_: IOException) {
                    null
                }
            }
            val current = ready ?: return@launch
            mutableState.value = if (png == null) {
                current.copy(busy = false, problem = DocumentSettingsProblem.NOT_AN_IMAGE)
            } else {
                current.copy(busy = false, settings = current.settings.copy(logo = DocumentLogo(png)))
            }
        }
    }

    /** Saves the settings shown. */
    fun save() {
        val shown = ready?.takeIf { !it.busy } ?: return
        mutableState.value = shown.copy(busy = true, problem = null)
        viewModelScope.launch {
            val idle = shown.copy(busy = false)
            mutableState.value = when (storageCall { saveSettings(shown.settings) }.getOrNull()) {
                SaveDocumentSettings.Result.Saved -> idle.copy(saved = true)
                SaveDocumentSettings.Result.FooterTooLong ->
                    idle.copy(problem = DocumentSettingsProblem.FOOTER_TOO_LONG)
                null -> idle.copy(problem = DocumentSettingsProblem.NOT_SAVED)
            }
        }
    }

    private fun change(change: (DocumentSettings) -> DocumentSettings) {
        ready?.let { mutableState.value = it.copy(settings = change(it.settings), problem = null, saved = false) }
    }
}
