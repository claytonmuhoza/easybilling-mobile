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

/**
 * Home screen wired to its view model.
 *
 * @param userName name of the logged in user.
 * @param onLogOut called when the user asks to log out.
 * @param onOpenTaxProfile called when the user opens the tax profile.
 * @param viewModel holder of the home state.
 */
@Composable
fun HomeRoute(
    userName: String,
    onLogOut: () -> Unit,
    onOpenTaxProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val status by viewModel.taxProfileStatus.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the profile may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    HomeScreen(userName = userName, taxProfileStatus = status, onLogOut = onLogOut, onOpenTaxProfile = onOpenTaxProfile)
}

/**
 * Home screen: a welcome and the settings to complete before selling.
 *
 * @param userName name of the logged in user.
 * @param taxProfileStatus where the tax profile stands.
 * @param onLogOut called when the user asks to log out.
 * @param onOpenTaxProfile called when the user opens the tax profile.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun HomeScreen(
    userName: String,
    taxProfileStatus: TaxProfileStatus,
    onLogOut: () -> Unit,
    onOpenTaxProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TitledScreen(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = { TextAction(label = stringResource(R.string.home_log_out), onClick = onLogOut) },
    ) {
        FormColumn {
            Text(text = stringResource(R.string.home_welcome, userName), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.home_empty_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader(stringResource(R.string.home_settings))
            OutlinedCard(onClick = onOpenTaxProfile, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tax_profile_title)) },
                    supportingContent = { Text(taxProfileStatus.text()) },
                )
            }
        }
    }
}

@Composable
private fun TaxProfileStatus.text(): String = when (this) {
    TaxProfileStatus.Loading -> "…"
    TaxProfileStatus.Unreadable -> stringResource(R.string.home_tax_profile_unreadable)
    TaxProfileStatus.ToComplete -> stringResource(R.string.home_tax_profile_to_complete)
    is TaxProfileStatus.Saved -> stringResource(R.string.home_tax_profile_version, number)
}
