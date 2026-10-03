package com.claytonmuhoza.easybilling.taxprofile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.ui.dateTimeText

/**
 * Tax profile history wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenVersion called with the number of the version the user picks.
 * @param viewModel holder of the history state.
 */
@Composable
fun TaxProfileHistoryRoute(
    onBack: () -> Unit,
    onOpenVersion: (Int) -> Unit,
    viewModel: TaxProfileHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaxProfileHistoryScreen(state = state, onBack = onBack, onOpenVersion = onOpenVersion, onRetry = viewModel::load)
}

/**
 * Every saved version of the tax profile, newest first.
 *
 * @param state the history state.
 * @param onBack called when the user goes back.
 * @param onOpenVersion called with the number of the version the user picks.
 * @param onRetry called when the user asks to read the history again.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TaxProfileHistoryScreen(
    state: TaxProfileHistoryUiState,
    onBack: () -> Unit,
    onOpenVersion: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TitledScreen(title = stringResource(R.string.tax_profile_history_title), modifier = modifier, onBack = onBack) {
        when (state) {
            TaxProfileHistoryUiState.Loading -> LoadingState()
            TaxProfileHistoryUiState.Failed -> ErrorState(
                message = stringResource(R.string.tax_profile_load_error),
                onRetry = onRetry,
            )
            is TaxProfileHistoryUiState.Ready -> if (state.versions.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.tax_profile_history_empty_title),
                    message = stringResource(R.string.tax_profile_history_empty_message),
                )
            } else {
                FormColumn {
                    state.versions.forEach { version ->
                        TonalCard(onClick = { onOpenVersion(version.number) }, modifier = Modifier.fillMaxWidth()) {
                            CardListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.tax_profile_version_title, version.number))
                                },
                                supportingContent = { Text(savedText(version)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun savedText(version: VersionSummary): String = stringResource(
    R.string.tax_profile_version_saved,
    dateTimeText(version.savedAt),
    version.authorName ?: stringResource(R.string.tax_profile_version_unknown_author),
)
