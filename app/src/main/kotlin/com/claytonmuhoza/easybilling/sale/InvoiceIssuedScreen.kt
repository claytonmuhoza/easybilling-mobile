package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.navigation.InvoiceIssuedDestination
import com.claytonmuhoza.easybilling.ui.amountText
import com.claytonmuhoza.easybilling.ui.dateTimeText
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the screen shown once a sale is issued. */
sealed interface InvoiceIssuedUiState {
    /** The invoice is being read. */
    data object Loading : InvoiceIssuedUiState

    /** The invoice could not be read. */
    data object Failed : InvoiceIssuedUiState

    /** There is no invoice with that identifier. */
    data object NotFound : InvoiceIssuedUiState

    /** The invoice [invoice] and where it stands with the OBR. */
    data class Ready(val invoice: Invoice, val status: InvoiceStatus) : InvoiceIssuedUiState
}

/** Reads the invoice that was just issued. */
@HiltViewModel
class InvoiceIssuedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val invoices: InvoiceRepository,
) : ViewModel() {

    private val id = InvoiceId(savedStateHandle.toRoute<InvoiceIssuedDestination>().id)

    private val mutableState = MutableStateFlow<InvoiceIssuedUiState>(InvoiceIssuedUiState.Loading)

    /** The state to show. */
    val state: StateFlow<InvoiceIssuedUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the invoice. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall {
                val invoice = invoices.find(id)
                val status = invoices.statusOf(id)
                if (invoice != null && status != null) {
                    InvoiceIssuedUiState.Ready(invoice, status)
                } else {
                    InvoiceIssuedUiState.NotFound
                }
            }.getOrElse { InvoiceIssuedUiState.Failed }
        }
    }
}

/**
 * Confirmation of an issue wired to its view model.
 *
 * @param onNewSale called when the user starts another sale.
 * @param onHome called when the user goes back to the home screen.
 * @param viewModel holder of the invoice state.
 */
@Composable
fun InvoiceIssuedRoute(
    onNewSale: () -> Unit,
    onHome: () -> Unit,
    viewModel: InvoiceIssuedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InvoiceIssuedScreen(state, InvoiceIssuedActions(onNewSale = onNewSale, onHome = onHome, onRetry = viewModel::load))
}

/**
 * What the user can do once a sale is issued.
 *
 * @property onNewSale called when the user starts another sale.
 * @property onHome called when the user goes back to the home screen.
 * @property onRetry called when the user asks to read the invoice again.
 */
class InvoiceIssuedActions(
    val onNewSale: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * What was just issued: the number, the identifier printed on every invoice, the customer, the
 * total and where the invoice stands with the OBR.
 *
 * @param state the invoice state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun InvoiceIssuedScreen(state: InvoiceIssuedUiState, actions: InvoiceIssuedActions, modifier: Modifier = Modifier) {
    TitledScreen(title = stringResource(R.string.invoice_issued_title), modifier = modifier, onBack = actions.onHome) {
        when (state) {
            InvoiceIssuedUiState.Loading -> LoadingState()
            InvoiceIssuedUiState.Failed -> ErrorState(
                message = stringResource(R.string.invoice_load_error),
                onRetry = actions.onRetry,
            )
            InvoiceIssuedUiState.NotFound -> ErrorState(message = stringResource(R.string.invoice_not_found))
            is InvoiceIssuedUiState.Ready -> FormColumn {
                val invoice = state.invoice.content
                Text(
                    text = stringResource(R.string.invoice_issued_number, invoice.number),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(text = state.status.label(), color = MaterialTheme.colorScheme.primary)
                Fact(stringResource(R.string.invoice_issued_total), amountText(invoice.totals.total))
                Fact(stringResource(R.string.sale_customer), invoice.customer.name)
                Fact(stringResource(R.string.sale_payment), invoice.paymentType.label())
                Fact(stringResource(R.string.invoice_issued_date), dateTimeText(invoice.issuedAt))
                Fact(stringResource(R.string.invoice_issued_identifier), invoice.identifier)
                Button(onClick = actions.onNewSale, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invoice_issued_new_sale))
                }
                OutlinedButton(onClick = actions.onHome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.invoice_issued_home))
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    SectionHeader(label)
    Text(text = value, style = MaterialTheme.typography.bodyLarge)
}
