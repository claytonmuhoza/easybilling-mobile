package com.claytonmuhoza.easybilling.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrintTestPage
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.PrinterSettings
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.ReceiptPrinter
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the settings of the printer. */
sealed interface PrinterSettingsUiState {
    /** The settings are being read. */
    data object Loading : PrinterSettingsUiState

    /** The settings could not be read. */
    data object Failed : PrinterSettingsUiState

    /**
     * The settings are shown.
     *
     * @property settings the associated printer and how it prints.
     * @property lookup the printers the device is paired with, or why they cannot be listed.
     * @property testOutcome how the last test page ended, or null.
     * @property testing true while a test page is being printed.
     * @property notSaved true when the last change could not be written.
     */
    data class Ready(
        val settings: PrinterSettings,
        val lookup: PrinterLookup,
        val testOutcome: PrintOutcome? = null,
        val testing: Boolean = false,
        val notSaved: Boolean = false,
    ) : PrinterSettingsUiState
}

/**
 * Associates the printer of the tickets and sets how it prints (UC-A09). Each change is saved at
 * once: there is nothing to confirm.
 */
@HiltViewModel
class PrinterSettingsViewModel @Inject constructor(
    private val repository: PrinterSettingsRepository,
    private val printer: ReceiptPrinter,
    private val printTestPage: PrintTestPage,
) : ViewModel() {

    private val mutableState = MutableStateFlow<PrinterSettingsUiState>(PrinterSettingsUiState.Loading)

    /** The state to show. */
    val state: StateFlow<PrinterSettingsUiState> = mutableState.asStateFlow()

    private val ready get() = mutableState.value as? PrinterSettingsUiState.Ready

    /** Reads the settings and the paired printers. Called when the screen is shown, and to check again. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { PrinterSettingsUiState.Ready(repository.read(), printer.paired()) }
                .getOrElse { PrinterSettingsUiState.Failed }
        }
    }

    /** @param chosen the printer to associate, or null to associate none. */
    fun onPrinterChosen(chosen: PairedPrinter?) = change { it.copy(printer = chosen) }

    /** @param width the paper of the printer. */
    fun onWidthChosen(width: ReceiptWidth) = change { it.copy(width = width) }

    /** @param accents whether the printer prints accented characters. */
    fun onAccentsChange(accents: Boolean) = change { it.copy(accents = accents) }

    /** @param autoPrint whether a ticket is printed as soon as a sale is issued. */
    fun onAutoPrintChange(autoPrint: Boolean) = change { it.copy(autoPrint = autoPrint) }

    /** Prints a page that shows whether the width and the accents are set right. */
    fun printTest() {
        val shown = ready?.takeIf { !it.testing } ?: return
        mutableState.value = shown.copy(testing = true, testOutcome = null)
        viewModelScope.launch {
            val outcome = storageCall { printTestPage() }.getOrDefault(PrintOutcome.UNREACHABLE)
            ready?.let { mutableState.value = it.copy(testing = false, testOutcome = outcome) }
        }
    }

    private fun change(change: (PrinterSettings) -> PrinterSettings) {
        val shown = ready ?: return
        val wanted = change(shown.settings)
        viewModelScope.launch {
            val saved = storageCall { repository.write(wanted) }.isSuccess
            ready?.let {
                mutableState.value = it.copy(
                    settings = if (saved) wanted else it.settings,
                    notSaved = !saved,
                    testOutcome = null,
                )
            }
        }
    }
}
