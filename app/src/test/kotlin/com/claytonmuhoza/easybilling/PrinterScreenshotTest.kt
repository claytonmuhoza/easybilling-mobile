package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.documents.PrinterSettingsActions
import com.claytonmuhoza.easybilling.documents.PrinterSettingsScreen
import com.claytonmuhoza.easybilling.documents.PrinterSettingsUiState
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrintOutcome
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.PrinterSettings
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the settings of the printer, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PrinterScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val pos58 = PairedPrinter("00:11:22:33:44:55", "POS-58")
    private val epson = PairedPrinter("00:11:22:33:44:66", "Epson TM-T20")
    private val paired = PrinterLookup.Found(listOf(epson, pos58))

    @Composable
    private fun screen(state: PrinterSettingsUiState) = PrinterSettingsScreen(state, PrinterSettingsActions())

    @Test
    fun printerNoneAssociated() = capture("printer_settings") {
        screen(PrinterSettingsUiState.Ready(PrinterSettings(), paired))
    }

    @Test
    fun printerAssociated() = capture("printer_settings_associated") {
        val settings = PrinterSettings(pos58, ReceiptWidth.MM_80, accents = true, autoPrint = true)
        screen(PrinterSettingsUiState.Ready(settings, paired, testOutcome = PrintOutcome.PRINTED))
    }

    @Test
    fun printerUnreachable() = capture("printer_settings_unreachable") {
        val state = PrinterSettingsUiState.Ready(PrinterSettings(pos58), paired, testOutcome = PrintOutcome.UNREACHABLE)
        screen(state)
    }

    @Test
    fun printerNoLongerPaired() = capture("printer_settings_no_longer_paired") {
        screen(PrinterSettingsUiState.Ready(PrinterSettings(pos58), PrinterLookup.Found(listOf(epson))))
    }

    @Test
    fun printerNonePaired() = capture("printer_settings_none_paired") {
        screen(PrinterSettingsUiState.Ready(PrinterSettings(), PrinterLookup.Found(emptyList())))
    }

    @Test
    fun printerBluetoothOff() = capture("printer_settings_bluetooth_off") {
        screen(PrinterSettingsUiState.Ready(PrinterSettings(), PrinterLookup.BluetoothOff))
    }

    @Test
    fun printerPermissionMissing() = capture("printer_settings_permission") {
        screen(PrinterSettingsUiState.Ready(PrinterSettings(), PrinterLookup.PermissionMissing))
    }

    @Test
    fun printerLoading() = capture("printer_settings_loading", animated = true) {
        screen(PrinterSettingsUiState.Loading)
    }

    @Test
    fun printerError() = capture("printer_settings_error") {
        screen(PrinterSettingsUiState.Failed)
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
