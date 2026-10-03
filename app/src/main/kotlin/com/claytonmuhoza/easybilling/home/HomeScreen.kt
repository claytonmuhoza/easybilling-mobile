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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.ebms.label
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Home screen wired to its view model.
 *
 * @param userName name of the logged in user.
 * @param onLogOut called when the user asks to log out.
 * @param onOpenTaxProfile called when the user opens the tax profile.
 * @param onOpenEbmsConnection called when the user opens the connection to the EBMS.
 * @param viewModel holder of the home state.
 */
@Composable
fun HomeRoute(
    userName: String,
    onLogOut: () -> Unit,
    onOpenTaxProfile: () -> Unit,
    onOpenEbmsConnection: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val taxProfileStatus by viewModel.taxProfileStatus.collectAsStateWithLifecycle()
    val ebmsStatus by viewModel.ebmsStatus.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the settings may have changed on their own screens.
    LaunchedEffect(Unit) { viewModel.load() }

    HomeScreen(
        userName = userName,
        taxProfileStatus = taxProfileStatus,
        ebmsStatus = ebmsStatus,
        actions = HomeActions(onLogOut, onOpenTaxProfile, onOpenEbmsConnection),
    )
}

/**
 * What the user can do on the home screen.
 *
 * @property onLogOut called when the user asks to log out.
 * @property onOpenTaxProfile called when the user opens the tax profile.
 * @property onOpenEbmsConnection called when the user opens the connection to the EBMS.
 */
class HomeActions(
    val onLogOut: () -> Unit = {},
    val onOpenTaxProfile: () -> Unit = {},
    val onOpenEbmsConnection: () -> Unit = {},
)

/**
 * Home screen: a welcome and the settings to complete before selling.
 *
 * @param userName name of the logged in user.
 * @param taxProfileStatus where the tax profile stands.
 * @param ebmsStatus where the connection to the EBMS stands.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun HomeScreen(
    userName: String,
    taxProfileStatus: TaxProfileStatus,
    ebmsStatus: EbmsStatus,
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
            SettingCard(stringResource(R.string.tax_profile_title), taxProfileStatus.text(), actions.onOpenTaxProfile)
            SettingCard(stringResource(R.string.ebms_title), ebmsStatus.text(), actions.onOpenEbmsConnection)
        }
    }
}

@Composable
private fun SettingCard(title: String, status: String, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(status) })
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
