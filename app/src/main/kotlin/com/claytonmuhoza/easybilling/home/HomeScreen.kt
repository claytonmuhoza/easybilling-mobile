package com.claytonmuhoza.easybilling.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.ebms.label
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Home screen wired to its view model.
 *
 * @param userName name of the logged in user.
 * @param onLogOut called when the user asks to log out.
 * @param onOpenTaxProfile called when the user opens the tax profile.
 * @param onOpenEbmsConnection called when the user opens the connection to the EBMS.
 * @param onOpenTaxes called when the user opens the taxes.
 * @param onOpenNumbering called when the user opens the numbering.
 * @param onOpenItems called when the user opens the items.
 * @param viewModel holder of the home state.
 */
@Composable
fun HomeRoute(
    userName: String,
    onLogOut: () -> Unit,
    onOpenTaxProfile: () -> Unit,
    onOpenEbmsConnection: () -> Unit,
    onOpenTaxes: () -> Unit,
    onOpenNumbering: () -> Unit,
    onOpenItems: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val taxProfileStatus by viewModel.taxProfileStatus.collectAsStateWithLifecycle()
    val ebmsStatus by viewModel.ebmsStatus.collectAsStateWithLifecycle()
    val taxesStatus by viewModel.taxesStatus.collectAsStateWithLifecycle()
    val numberingStatus by viewModel.numberingStatus.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val catalogStatus by viewModel.catalogStatus.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the settings may have changed on their own screens.
    LaunchedEffect(Unit) { viewModel.load() }

    HomeScreen(
        userName = userName,
        taxProfileStatus = taxProfileStatus,
        ebmsStatus = ebmsStatus,
        taxesStatus = taxesStatus,
        numberingStatus = numberingStatus,
        readiness = readiness,
        catalogStatus = catalogStatus,
        actions = HomeActions(
            onLogOut = onLogOut,
            onOpenTaxProfile = onOpenTaxProfile,
            onOpenEbmsConnection = onOpenEbmsConnection,
            onOpenTaxes = onOpenTaxes,
            onOpenNumbering = onOpenNumbering,
            onOpenItems = onOpenItems,
        ),
    )
}

/**
 * What the user can do on the home screen.
 *
 * @property onLogOut called when the user asks to log out.
 * @property onOpenTaxProfile called when the user opens the tax profile.
 * @property onOpenEbmsConnection called when the user opens the connection to the EBMS.
 * @property onOpenTaxes called when the user opens the taxes.
 * @property onOpenNumbering called when the user opens the numbering.
 * @property onOpenItems called when the user opens the items.
 */
class HomeActions(
    val onLogOut: () -> Unit = {},
    val onOpenTaxProfile: () -> Unit = {},
    val onOpenEbmsConnection: () -> Unit = {},
    val onOpenTaxes: () -> Unit = {},
    val onOpenNumbering: () -> Unit = {},
    val onOpenItems: () -> Unit = {},
)

/**
 * Home screen: a welcome, what is left to do before the first invoice and the settings.
 *
 * @param userName name of the logged in user.
 * @param taxProfileStatus where the tax profile stands.
 * @param ebmsStatus where the connection to the EBMS stands.
 * @param taxesStatus where the taxes stand.
 * @param numberingStatus where the numbering stands.
 * @param readiness what is left to configure before the first invoice.
 * @param catalogStatus where the catalogue stands.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun HomeScreen(
    userName: String,
    taxProfileStatus: TaxProfileStatus,
    ebmsStatus: EbmsStatus,
    taxesStatus: TaxesStatus,
    numberingStatus: NumberingStatus,
    readiness: ReadinessStatus,
    catalogStatus: CatalogStatus,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    TitledScreen(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = { TextAction(label = stringResource(R.string.home_log_out), onClick = actions.onLogOut) },
    ) {
        FormColumn {
            Text(text = stringResource(R.string.home_welcome, userName), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.home_empty_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.home_settings))
            ReadinessSummary(readiness)
            SettingCard(
                title = stringResource(R.string.tax_profile_title),
                status = taxProfileStatus.text(),
                done = readiness.done(ReadinessStep.TAX_PROFILE),
                onOpen = actions.onOpenTaxProfile,
            )
            SettingCard(
                title = stringResource(R.string.ebms_title),
                status = ebmsStatus.text(),
                done = readiness.done(ReadinessStep.EBMS_CONNECTION),
                onOpen = actions.onOpenEbmsConnection,
            )
            SettingCard(
                title = stringResource(R.string.taxes_title),
                status = taxesStatus.text(),
                done = readiness.done(ReadinessStep.VAT),
                onOpen = actions.onOpenTaxes,
            )
            SettingCard(
                title = stringResource(R.string.numbering_title),
                status = numberingStatus.text(),
                done = readiness.done(ReadinessStep.NUMBERING),
                onOpen = actions.onOpenNumbering,
            )
            SectionHeader(stringResource(R.string.home_catalog))
            SettingCard(
                title = stringResource(R.string.items_title),
                status = catalogStatus.text(),
                done = null,
                onOpen = actions.onOpenItems,
            )
        }
    }
}

/**
 * A setting to open. [done] tells whether it is ready for the first invoice; null shows nothing,
 * for a setting that is not required or not read yet.
 */
@Composable
private fun SettingCard(title: String, status: String, done: Boolean?, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(status) },
            trailingContent = done?.let {
                {
                    Text(
                        text = stringResource(if (it) R.string.home_readiness_done else R.string.home_readiness_to_do),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun TaxProfileStatus.text(): String = when (this) {
    TaxProfileStatus.Loading -> "…"
    TaxProfileStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    TaxProfileStatus.ToComplete -> stringResource(R.string.home_tax_profile_to_complete)
    is TaxProfileStatus.Saved -> stringResource(R.string.home_tax_profile_version, number)
}

@Composable
private fun EbmsStatus.text(): String = when (this) {
    EbmsStatus.Loading -> "…"
    EbmsStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    EbmsStatus.ToConfigure -> stringResource(R.string.home_ebms_to_configure)
    is EbmsStatus.Configured -> lastSuccess
        ?.let { stringResource(R.string.home_ebms_connected, environment.label(), dateTimeText(it)) }
        ?: stringResource(R.string.home_ebms_never_tested, environment.label())
}

@Composable
private fun TaxesStatus.text(): String = when (this) {
    TaxesStatus.Loading -> "…"
    TaxesStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is TaxesStatus.Defined -> if (active == 0) {
        stringResource(R.string.home_taxes_none)
    } else {
        pluralStringResource(R.plurals.home_taxes_active, active, active)
    }
}

@Composable
private fun NumberingStatus.text(): String = when (this) {
    NumberingStatus.Loading -> "…"
    NumberingStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    NumberingStatus.ToDefine -> stringResource(R.string.home_numbering_to_define)
    is NumberingStatus.Defined -> if (locked) stringResource(R.string.home_numbering_locked, example) else example
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

@Composable
private fun CatalogStatus.text(): String = when (this) {
    CatalogStatus.Loading -> "…"
    CatalogStatus.Unreadable -> stringResource(R.string.home_status_unreadable)
    is CatalogStatus.Counted -> {
        val items = if (active == 0) {
            stringResource(R.string.home_items_none)
        } else {
            pluralStringResource(R.plurals.home_items_active, active, active)
        }
        val toComplete = pluralStringResource(R.plurals.home_items_without_vat, withoutVat, withoutVat)
        if (withoutVat == 0) items else "$items · $toComplete"
    }
}
