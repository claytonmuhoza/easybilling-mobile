package com.claytonmuhoza.easybilling.documents

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ChoiceField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.SwitchRow
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrinterLookup
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth

/**
 * Settings of the printer wired to their view model.
 *
 * @param onBack called when the user goes back.
 * @param viewModel holder of the settings state.
 */
@Composable
fun PrinterSettingsRoute(onBack: () -> Unit, viewModel: PrinterSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Whatever the user answers, the printers are looked for again.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.load() }

    // Read each time the screen appears: a printer may have been paired meanwhile.
    LaunchedEffect(Unit) { viewModel.load() }

    PrinterSettingsScreen(
        state = state,
        actions = PrinterSettingsActions(
            onBack = onBack,
            onPrinterChosen = viewModel::onPrinterChosen,
            onWidthChosen = viewModel::onWidthChosen,
            onAccentsChange = viewModel::onAccentsChange,
            onAutoPrintChange = viewModel::onAutoPrintChange,
            onPrintTest = viewModel::printTest,
            onCheckAgain = viewModel::load,
            onAllowBluetooth = {
                // The permission exists since Android 12; before, the connection needs no prompt.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    viewModel.load()
                }
            },
        ),
    )
}

/**
 * What the user can do on the settings of the printer.
 *
 * @property onBack called when the user goes back.
 * @property onPrinterChosen called with the printer to associate, or null for none.
 * @property onWidthChosen called with the paper of the printer.
 * @property onAccentsChange called when the user turns the accents on or off.
 * @property onAutoPrintChange called when the user turns the printing after a sale on or off.
 * @property onPrintTest called when the user asks for a test page.
 * @property onCheckAgain called when the user asks to look for the printers again.
 * @property onAllowBluetooth called when the user agrees to be asked for the Bluetooth permission.
 */
class PrinterSettingsActions(
    val onBack: () -> Unit = {},
    val onPrinterChosen: (PairedPrinter?) -> Unit = {},
    val onWidthChosen: (ReceiptWidth) -> Unit = {},
    val onAccentsChange: (Boolean) -> Unit = {},
    val onAutoPrintChange: (Boolean) -> Unit = {},
    val onPrintTest: () -> Unit = {},
    val onCheckAgain: () -> Unit = {},
    val onAllowBluetooth: () -> Unit = {},
)

/**
 * The printer of the tickets (UC-A09): which paired printer, its paper, whether it prints accents,
 * whether a ticket is printed after each sale, and a test page.
 *
 * @param state the settings state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun PrinterSettingsScreen(
    state: PrinterSettingsUiState,
    actions: PrinterSettingsActions,
    modifier: Modifier = Modifier,
) {
    TitledScreen(title = stringResource(R.string.printer_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            PrinterSettingsUiState.Loading -> LoadingState()
            PrinterSettingsUiState.Failed -> ErrorState(
                message = stringResource(R.string.printer_load_error),
                onRetry = actions.onCheckAgain,
            )
            is PrinterSettingsUiState.Ready -> FormColumn {
                Text(stringResource(R.string.printer_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Printers(state, actions)
                Paper(state, actions)
                Button(
                    onClick = actions.onPrintTest,
                    enabled = state.settings.printer != null && !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (state.testing) R.string.print_running else R.string.printer_test_page))
                }
                state.testOutcome?.let { PrintFailure(it) }
                if (state.notSaved) StatusBanner(StatusTone.ERROR, stringResource(R.string.printer_not_saved))
            }
        }
    }
}

/** The paired printers to choose from, or why they cannot be listed and what to do about it. */
@Composable
private fun Printers(state: PrinterSettingsUiState.Ready, actions: PrinterSettingsActions) {
    SectionHeader(stringResource(R.string.printer_paired))
    val associated = state.settings.printer
    when (val lookup = state.lookup) {
        PrinterLookup.BluetoothOff -> {
            StatusBanner(StatusTone.PENDING, stringResource(R.string.printer_bluetooth_off))
            OutlinedButton(onClick = actions.onCheckAgain) { Text(stringResource(R.string.printer_check_again)) }
        }
        PrinterLookup.PermissionMissing -> {
            Text(stringResource(R.string.printer_permission_needed))
            Button(onClick = actions.onAllowBluetooth) { Text(stringResource(R.string.printer_allow)) }
        }
        is PrinterLookup.Found -> {
            if (lookup.printers.isEmpty() && associated == null) {
                Text(stringResource(R.string.printer_none_paired), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PrinterChoice(stringResource(R.string.printer_none), address = null, selected = associated == null) {
                actions.onPrinterChosen(null)
            }
            // The associated printer stays listed when it is no longer paired, so it can be seen and replaced.
            if (associated != null && associated !in lookup.printers) {
                val gone = stringResource(R.string.printer_not_paired_any_more, associated.name)
                PrinterChoice(gone, associated.address, selected = true) { actions.onPrinterChosen(associated) }
            }
            lookup.printers.forEach { printer ->
                PrinterChoice(printer.name, printer.address, printer == associated) { actions.onPrinterChosen(printer) }
            }
            OutlinedButton(onClick = actions.onCheckAgain) { Text(stringResource(R.string.printer_check_again)) }
        }
    }
}

/** One choice among the printers: a radio button, said as such by the screen readers. */
@Composable
private fun PrinterChoice(name: String, address: String?, selected: Boolean, onChoose: () -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onChoose)) {
        CardListItem(
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            headlineContent = { Text(name) },
            supportingContent = address?.let { { Text(it) } },
        )
    }
}

/** The paper, the accents and the printing after a sale. */
@Composable
private fun Paper(state: PrinterSettingsUiState.Ready, actions: PrinterSettingsActions) {
    ChoiceField(
        label = stringResource(R.string.printer_width),
        options = listOf(
            ReceiptWidth.MM_58 to stringResource(R.string.printer_width_58),
            ReceiptWidth.MM_80 to stringResource(R.string.printer_width_80),
        ),
        selected = state.settings.width,
        onSelect = actions.onWidthChosen,
    )
    SwitchRow(
        label = stringResource(R.string.printer_accents),
        checked = state.settings.accents,
        onCheckedChange = actions.onAccentsChange,
    )
    Text(
        text = stringResource(R.string.printer_accents_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow(
        label = stringResource(R.string.printer_auto_print),
        checked = state.settings.autoPrint,
        onCheckedChange = actions.onAutoPrintChange,
    )
}
