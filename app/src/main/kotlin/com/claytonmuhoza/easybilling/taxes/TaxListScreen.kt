package com.claytonmuhoza.easybilling.taxes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StateAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.dateText
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** State of the list of the taxes. */
sealed interface TaxListUiState {
    /** The taxes are being read. */
    data object Loading : TaxListUiState

    /** The taxes could not be read. */
    data object Failed : TaxListUiState

    /**
     * The taxes are shown.
     *
     * @property taxes every tax, archived ones included.
     * @property today the day of the business, which decides the rate in force.
     */
    data class Ready(val taxes: List<TaxDefinition>, val today: LocalDate) : TaxListUiState
}

/** Lists the taxes the administrator defined (UC-A07). */
@HiltViewModel
class TaxListViewModel @Inject constructor(
    private val taxes: TaxRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val mutableState = MutableStateFlow<TaxListUiState>(TaxListUiState.Loading)

    /** The state to show. */
    val state: StateFlow<TaxListUiState> = mutableState.asStateFlow()

    /** Reads the taxes. Called each time the screen is shown, and to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { TaxListUiState.Ready(taxes.all(), BusinessCalendar.today(time)) }
                .getOrElse { TaxListUiState.Failed }
        }
    }
}

/**
 * List of the taxes wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpenTax called with the identifier of the tax the user picks.
 * @param onNewTax called when the user asks for a new tax.
 * @param onOpenPreview called when the user opens the preview of a line.
 * @param viewModel holder of the list state.
 */
@Composable
fun TaxListRoute(
    onBack: () -> Unit,
    onOpenTax: (Long) -> Unit,
    onNewTax: () -> Unit,
    onOpenPreview: () -> Unit,
    viewModel: TaxListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a tax may have changed on its own screen.
    LaunchedEffect(Unit) { viewModel.load() }

    TaxListScreen(
        state = state,
        actions = TaxListActions(onBack, onOpenTax, onNewTax, onOpenPreview, viewModel::load),
    )
}

/**
 * What the user can do on the list of the taxes.
 *
 * @property onBack called when the user goes back.
 * @property onOpenTax called with the identifier of the tax the user picks.
 * @property onNewTax called when the user asks for a new tax.
 * @property onOpenPreview called when the user opens the preview of a line.
 * @property onRetry called when the user asks to read the taxes again.
 */
class TaxListActions(
    val onBack: () -> Unit = {},
    val onOpenTax: (Long) -> Unit = {},
    val onNewTax: () -> Unit = {},
    val onOpenPreview: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The taxes grouped by kind, each with its rate in force (UC-A07).
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun TaxListScreen(state: TaxListUiState, actions: TaxListActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.taxes_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.taxes_preview), onClick = actions.onOpenPreview) },
    ) {
        when (state) {
            TaxListUiState.Loading -> LoadingState()
            TaxListUiState.Failed -> ErrorState(
                message = stringResource(R.string.taxes_load_error),
                onRetry = actions.onRetry,
            )
            is TaxListUiState.Ready -> if (state.taxes.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.taxes_empty_title),
                    message = stringResource(R.string.taxes_empty_message),
                    action = StateAction(stringResource(R.string.taxes_new), actions.onNewTax),
                )
            } else {
                FormColumn {
                    Button(onClick = actions.onNewTax, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.taxes_new))
                    }
                    TaxKind.entries.forEach { kind ->
                        val ofKind = state.taxes.filter { it.kind == kind }
                        if (ofKind.isNotEmpty()) {
                            SectionHeader(kind.longLabel())
                            ofKind.forEach { TaxCard(it, state.today, actions.onOpenTax) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaxCard(tax: TaxDefinition, today: LocalDate, onOpen: (Long) -> Unit) {
    val current = tax.computationAt(today)
    val upcoming = tax.versions.filter { it.validFrom.isAfter(today) }.minByOrNull { it.validFrom }
    val firstDay = tax.versions.minOf { it.validFrom }
    val lines = listOfNotNull(
        current?.describe(tax.kind) ?: stringResource(R.string.taxes_not_yet, dateText(firstDay)),
        upcoming?.takeIf { current != null }?.let {
            stringResource(R.string.taxes_next_rate, dateText(it.validFrom), it.computation.describe(tax.kind))
        },
        stringResource(R.string.taxes_archived).takeIf { tax.archived },
    )
    TonalCard(onClick = { onOpen(tax.id.value) }, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            headlineContent = { Text(tax.name) },
            supportingContent = { Text(lines.joinToString("\n")) },
        )
    }
}
