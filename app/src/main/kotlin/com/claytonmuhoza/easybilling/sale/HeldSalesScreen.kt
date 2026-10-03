package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.EmptyState
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.PricedSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.dateTimeText
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the list of the sales on hold. */
sealed interface HeldSalesUiState {
    /** The sales are being read. */
    data object Loading : HeldSalesUiState

    /** The sales could not be read or changed. */
    data object Failed : HeldSalesUiState

    /** A sale went back to the cash register: the screen can close. */
    data object Resumed : HeldSalesUiState

    /** The sales on hold, with their amounts of today, the most recent first. */
    data class Ready(val sales: List<PricedSale>) : HeldSalesUiState
}

/** Lists the sales on hold, resumes one or deletes it (UC-C02). */
@HiltViewModel
class HeldSalesViewModel @Inject constructor(
    private val drafts: SaleDraftRepository,
    private val priceSale: PriceSale,
    private val editSale: EditSale,
) : ViewModel() {

    private val mutableState = MutableStateFlow<HeldSalesUiState>(HeldSalesUiState.Loading)

    /** The state to show. */
    val state: StateFlow<HeldSalesUiState> = mutableState.asStateFlow()

    /** Reads the sales on hold. Called each time the screen is shown, and to retry. */
    fun load() = act { read() }

    /** @param id the sale that goes back to the cash register. */
    fun resume(id: Long) = act { if (editSale.resume(DraftId(id))) HeldSalesUiState.Resumed else read() }

    /** @param id the sale to delete: it has no number, so nothing fiscal is lost. */
    fun delete(id: Long) = act {
        editSale.discard(DraftId(id))
        read()
    }

    private fun act(block: suspend () -> HeldSalesUiState) {
        viewModelScope.launch {
            mutableState.value = storageCall { block() }.getOrElse { HeldSalesUiState.Failed }
        }
    }

    private suspend fun read() = HeldSalesUiState.Ready(drafts.held().map { priceSale(it) })
}

/**
 * Sales on hold wired to their view model.
 *
 * @param onClose called to leave the screen, once a sale is resumed or when the user goes back.
 * @param viewModel holder of the list state.
 */
@Composable
fun HeldSalesRoute(onClose: () -> Unit, viewModel: HeldSalesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    if (state == HeldSalesUiState.Resumed) {
        LaunchedEffect(Unit) { onClose() }
    }

    HeldSalesScreen(
        state = state,
        actions = HeldSalesActions(
            onBack = onClose,
            onResume = viewModel::resume,
            onDelete = viewModel::delete,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do on the sales on hold.
 *
 * @property onBack called when the user goes back.
 * @property onResume called with the identifier of the sale to take back to the cash register.
 * @property onDelete called with the identifier of the sale to delete.
 * @property onRetry called when the user asks to read the sales again.
 */
class HeldSalesActions(
    val onBack: () -> Unit = {},
    val onResume: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The sales put on hold (UC-C02).
 *
 * @param state the list state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun HeldSalesScreen(state: HeldSalesUiState, actions: HeldSalesActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.held_sales_title), modifier = modifier, onBack = actions.onBack) {
        when (state) {
            HeldSalesUiState.Loading, HeldSalesUiState.Resumed -> LoadingState()
            HeldSalesUiState.Failed -> ErrorState(
                message = stringResource(R.string.held_sales_load_error),
                onRetry = actions.onRetry,
            )
            is HeldSalesUiState.Ready -> if (state.sales.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.held_sales_empty_title),
                    message = stringResource(R.string.held_sales_empty_message),
                )
            } else {
                FormColumn { state.sales.forEach { HeldSaleCard(it, actions) } }
            }
        }
    }
}

@Composable
private fun HeldSaleCard(sale: PricedSale, actions: HeldSalesActions) {
    val customer = sale.customer.details.name
    val id = sale.draft.id.value
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(text = customer, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(
                    R.plurals.held_sale_summary,
                    sale.lines.size,
                    sale.lines.size,
                    amountText(sale.totals.total),
                ),
            )
            Text(
                text = stringResource(R.string.held_sale_since, dateTimeText(sale.draft.updatedAt)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                val resume = stringResource(R.string.held_sale_resume_of, customer, id)
                Button(
                    onClick = { actions.onResume(id) },
                    modifier = Modifier.semantics { contentDescription = resume },
                ) {
                    Text(stringResource(R.string.held_sale_resume))
                }
                val delete = stringResource(R.string.held_sale_delete_of, customer, id)
                OutlinedButton(
                    onClick = { actions.onDelete(id) },
                    modifier = Modifier.semantics { contentDescription = delete },
                ) {
                    Text(stringResource(R.string.held_sale_delete))
                }
            }
        }
    }
}
