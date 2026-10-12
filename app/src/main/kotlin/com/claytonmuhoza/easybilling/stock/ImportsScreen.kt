package com.claytonmuhoza.easybilling.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusChip
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcLineProgress
import com.claytonmuhoza.easybilling.ui.dateTimeText
import com.claytonmuhoza.easybilling.ui.decimalText

/** Keeps the lists readable on a tablet instead of stretching their cards across the whole screen. */
private val ListMaxWidth = 600.dp

/**
 * List of the customs declarations wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onOpen called with the reference of the declaration the user picks or just read.
 * @param viewModel holder of the list state.
 */
@Composable
fun ImportsRoute(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: ImportsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: a declaration may have been read again.
    LaunchedEffect(Unit) { viewModel.load() }
    (state as? ImportsUiState.Ready)?.fetched?.let { reference ->
        LaunchedEffect(reference) {
            viewModel.onOpened()
            onOpen(reference)
        }
    }

    ImportsScreen(
        state = state,
        actions = ImportsActions(
            onBack = onBack,
            onOpen = onOpen,
            onReferenceChange = viewModel::onReferenceChange,
            onFetch = viewModel::fetch,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the list of the customs declarations.
 *
 * @property onBack called when the user goes back.
 * @property onOpen called with the reference of the declaration the user picks.
 * @property onReferenceChange called with the new content of the reference field.
 * @property onFetch called when the user asks the OBR for the declaration typed.
 * @property onRetry called when the user asks to read the declarations again.
 */
class ImportsActions(
    val onBack: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onReferenceChange: (String) -> Unit = {},
    val onFetch: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The reading of a customs declaration from the OBR (UC-F03) and the declarations already read.
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun ImportsScreen(state: ImportsUiState, actions: ImportsActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.imports_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            ImportsUiState.Loading -> LoadingState()
            ImportsUiState.Failed -> ErrorState(
                message = stringResource(R.string.imports_load_error),
                onRetry = actions.onRetry,
            )
            is ImportsUiState.Ready -> ImportsContent(state, actions)
        }
    }
}

@Composable
private fun ImportsContent(state: ImportsUiState.Ready, actions: ImportsActions) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
        contentPadding = PaddingValues(bottom = Spacing.large),
    ) {
        item { FetchForm(state, actions) }
        item {
            Text(
                text = stringResource(
                    if (state.declarations.isEmpty()) R.string.imports_none else R.string.imports_known,
                ),
                modifier = Modifier.padding(top = Spacing.medium),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.declarations, key = { it.reference }) { dmc -> DeclarationCard(dmc, actions.onOpen) }
    }
}

@Composable
private fun FetchForm(state: ImportsUiState.Ready, actions: ImportsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = stringResource(R.string.imports_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.reference,
            onValueChange = actions.onReferenceChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.imports_reference)) },
            isError = state.error == ImportsError.InvalidReference,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { actions.onFetch() }),
            singleLine = true,
        )
        state.error?.let { StatusBanner(StatusTone.ERROR, it.message()) }
        Button(onClick = actions.onFetch, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(stringResource(if (state.busy) R.string.imports_fetching else R.string.imports_fetch))
        }
    }
}

@Composable
private fun DeclarationCard(dmc: Dmc, onOpen: (String) -> Unit) {
    TonalCard(onClick = { onOpen(dmc.reference) }, modifier = Modifier.fillMaxWidth()) {
        CardListItem(
            leadingContent = { IconTile(AppIcon.INVENTORY) },
            headlineContent = { Text(dmc.reference) },
            supportingContent = {
                Column {
                    Text(pluralStringResource(R.plurals.imports_lines, dmc.lines.size, dmc.lines.size))
                    Text(stringResource(R.string.imports_read_on, dateTimeText(dmc.fetchedAt)))
                }
            },
        )
    }
}

/** @return why the declaration was not read, in words. */
@Composable
private fun ImportsError.message(): String = when (this) {
    ImportsError.InvalidReference -> stringResource(R.string.imports_error_reference)
    is ImportsError.Refused -> stringResource(R.string.imports_error_refused, message)
    ImportsError.Empty -> stringResource(R.string.imports_error_empty)
    ImportsError.Unavailable -> stringResource(R.string.imports_error_unavailable)
    ImportsError.NotConfigured -> stringResource(R.string.imports_error_not_configured)
    ImportsError.Failed -> stringResource(R.string.imports_error_failed)
}

/**
 * A customs declaration wired to its view model.
 *
 * @param onBack called when the user goes back.
 * @param onEnter called with the reference of the declaration and the rank of the line the user picks.
 * @param viewModel holder of the declaration state.
 */
@Composable
fun DmcRoute(onBack: () -> Unit, onEnter: (String, Int) -> Unit, viewModel: DmcViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: goods may have been entered on another screen.
    LaunchedEffect(Unit) { viewModel.load() }

    DmcScreen(
        reference = viewModel.reference,
        state = state,
        onBack = onBack,
        onEnter = { rank -> onEnter(viewModel.reference, rank) },
        onRetry = viewModel::load,
    )
}

/**
 * The lines of a customs declaration with what was entered of each (UC-F04).
 *
 * @param reference the reference of the declaration, shown as the title.
 * @param state the declaration state.
 * @param onBack called when the user goes back.
 * @param onEnter called with the rank of the line the user picks.
 * @param modifier modifier applied to the screen.
 * @param onRetry called when the user asks to read the declaration again.
 */
@Composable
fun DmcScreen(
    reference: String,
    state: DmcUiState,
    onBack: () -> Unit,
    onEnter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    TitledScreen(title = reference, modifier = modifier, onBack = onBack) {
        when (state) {
            DmcUiState.Loading -> LoadingState()
            DmcUiState.Failed -> ErrorState(message = stringResource(R.string.imports_load_error), onRetry = onRetry)
            DmcUiState.NotFound -> ErrorState(message = stringResource(R.string.dmc_not_found))
            is DmcUiState.Ready -> LazyColumn(
                modifier = Modifier
                    .widthIn(max = ListMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                contentPadding = PaddingValues(bottom = Spacing.large),
            ) {
                item {
                    Text(text = stringResource(R.string.dmc_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.progress.lines, key = { it.line.rank }) { line -> DmcLineCard(line, onEnter) }
            }
        }
    }
}

@Composable
private fun DmcLineCard(progress: DmcLineProgress, onEnter: (Int) -> Unit) {
    val line = progress.line
    TonalCard(onClick = { onEnter(line.rank) }, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = line.description,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.dmc_tariff, line.tariffHeading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.dmc_packaging, line.packaging),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(progress.quantitiesText())
            progress.item?.let { Text(stringResource(R.string.dmc_entered_as, it.details.designation)) }
            if (progress.remaining?.signum() == 0) {
                StatusChip(StatusTone.SUCCESS, stringResource(R.string.dmc_complete))
            }
        }
    }
}

/** @return what the declaration gives for the line, what was entered and what is left, in words. */
@Composable
internal fun DmcLineProgress.quantitiesText(): String {
    val declared = line.quantity
    val left = remaining
    return if (declared == null || left == null) {
        stringResource(R.string.dmc_quantities_unknown, decimalText(entered))
    } else {
        stringResource(R.string.dmc_quantities, decimalText(declared), decimalText(entered), decimalText(left))
    }
}
