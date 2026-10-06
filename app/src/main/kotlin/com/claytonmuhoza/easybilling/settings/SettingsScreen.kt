package com.claytonmuhoza.easybilling.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.access.allowed
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationRow
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.home.ReadinessStatus
import com.claytonmuhoza.easybilling.home.text

/**
 * Settings screen wired to its view model.
 *
 * @param userName name of the logged in user.
 * @param actions where the settings lead.
 * @param viewModel holder of the settings state.
 */
@Composable
fun SettingsRoute(userName: String, actions: SettingsActions, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a setting may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScreen(userName, state, actions)
}

/**
 * What the user can do on the settings screen.
 *
 * @property onBack called when the user goes back.
 * @property onLogOut called when the user asks to log out.
 * @property onOpenTaxProfile called when the user opens the tax profile.
 * @property onOpenEbmsConnection called when the user opens the connection to the EBMS.
 * @property onOpenTaxes called when the user opens the taxes.
 * @property onOpenNumbering called when the user opens the numbering.
 * @property onOpenDocuments called when the user opens the header of the invoices.
 * @property onOpenPrinter called when the user opens the printer.
 * @property onOpenUsers called when the user opens the accounts of the users.
 */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onLogOut: () -> Unit = {},
    val onOpenTaxProfile: () -> Unit = {},
    val onOpenEbmsConnection: () -> Unit = {},
    val onOpenTaxes: () -> Unit = {},
    val onOpenNumbering: () -> Unit = {},
    val onOpenDocuments: () -> Unit = {},
    val onOpenPrinter: () -> Unit = {},
    val onOpenUsers: () -> Unit = {},
)

/**
 * The settings: what must be configured before the first invoice, each with where it stands and
 * whether it is done, the look of the invoices, the users, and the logout. Each section is shown
 * only to the users allowed to change it.
 *
 * @param userName name of the logged in user.
 * @param state where each setting stands.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SettingsScreen(userName: String, state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.settings_title), modifier = modifier, onBack = actions.onBack) {
        FormColumn {
            if (allowed(Permission.CONFIGURE_BILLING)) BillingSection(state, actions)
            if (allowed(Permission.CONFIGURE_PRINTING)) DocumentsSection(state, actions)
            if (allowed(Permission.MANAGE_USERS)) {
                SectionHeader(stringResource(R.string.settings_access))
                NavigationRow(
                    title = stringResource(R.string.users_title),
                    onClick = actions.onOpenUsers,
                    supporting = stringResource(R.string.users_status),
                    icon = AppIcon.GROUP,
                )
            }

            SectionHeader(stringResource(R.string.settings_session))
            Text(
                text = stringResource(R.string.settings_logged_in_as, userName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.onLogOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_log_out))
            }
        }
    }
}

/** What must be configured before the first invoice. */
@Composable
private fun BillingSection(state: SettingsUiState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_billing))
    ReadinessSummary(state.readiness)
    SettingCard(
        title = stringResource(R.string.tax_profile_title),
        status = state.taxProfileStatus.text(),
        done = state.readiness.done(ReadinessStep.TAX_PROFILE),
        icon = AppIcon.BUSINESS,
        onOpen = actions.onOpenTaxProfile,
    )
    SettingCard(
        title = stringResource(R.string.ebms_title),
        status = state.ebmsStatus.text(),
        done = state.readiness.done(ReadinessStep.EBMS_CONNECTION),
        icon = AppIcon.CLOUD,
        onOpen = actions.onOpenEbmsConnection,
    )
    SettingCard(
        title = stringResource(R.string.taxes_title),
        status = state.taxesStatus.text(),
        done = state.readiness.done(ReadinessStep.VAT),
        icon = AppIcon.TAG,
        onOpen = actions.onOpenTaxes,
    )
    SettingCard(
        title = stringResource(R.string.numbering_title),
        status = state.numberingStatus.text(),
        done = state.readiness.done(ReadinessStep.NUMBERING),
        icon = AppIcon.TUNE,
        onOpen = actions.onOpenNumbering,
    )
}

/** The look of the invoices and the printer of the tickets. */
@Composable
private fun DocumentsSection(state: SettingsUiState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_documents))
    NavigationRow(
        title = stringResource(R.string.document_settings_title),
        onClick = actions.onOpenDocuments,
        supporting = stringResource(R.string.document_settings_status),
        icon = AppIcon.INVOICE,
    )
    NavigationRow(
        title = stringResource(R.string.printer_title),
        onClick = actions.onOpenPrinter,
        supporting = state.printerName ?: stringResource(R.string.printer_none),
        icon = AppIcon.TUNE,
    )
}

/**
 * A setting to open. [done] tells whether it is ready for the first invoice; null shows nothing,
 * for a setting that is not required or not read yet.
 */
@Composable
private fun SettingCard(title: String, status: String, done: Boolean?, icon: AppIcon, onOpen: () -> Unit) {
    NavigationRow(
        title = title,
        onClick = onOpen,
        supporting = status,
        icon = icon,
        trailing = done?.let {
            {
                StatusChip(
                    tone = if (it) StatusTone.SUCCESS else StatusTone.PENDING,
                    label = stringResource(if (it) R.string.home_readiness_done else R.string.home_readiness_to_do),
                )
            }
        },
    )
}

/** Whether invoices can be issued; the cards below show what is left to do. */
@Composable
private fun ReadinessSummary(status: ReadinessStatus) {
    val known = status as? ReadinessStatus.Known ?: return
    val ready = known.readiness.ready
    Text(
        text = stringResource(if (ready) R.string.home_readiness_ready else R.string.home_readiness_not_ready),
        style = MaterialTheme.typography.bodyMedium,
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** @return whether [step] is done, or null when it does not apply or is not known yet. */
private fun ReadinessStatus.done(step: ReadinessStep): Boolean? =
    (this as? ReadinessStatus.Known)?.readiness?.steps?.get(step)
