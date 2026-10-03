package com.claytonmuhoza.easybilling.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.NavigationRow
import com.claytonmuhoza.easybilling.core.designsystem.component.SearchField
import com.claytonmuhoza.easybilling.core.designsystem.component.TextAction
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.customers.summary
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the list readable on a tablet instead of stretching its cards across the whole screen. */
private val ListMaxWidth = 600.dp

/** State of the choice of the customer of a sale. */
sealed interface SaleCustomerUiState {
    /** The customers are being read. */
    data object Loading : SaleCustomerUiState

    /** The customers could not be read, or the choice could not be stored. */
    data object Failed : SaleCustomerUiState

    /** The customer is chosen: the screen can close. */
    data object Chosen : SaleCustomerUiState

    /**
     * The customers are shown.
     *
     * @property query what the user typed in the search field.
     * @property counter the counter customer, proposed first.
     * @property customers the active customers found.
     */
    data class Ready(val query: String, val counter: Customer, val customers: List<Customer>) : SaleCustomerUiState
}

/** Chooses the customer of the sale being typed (UC-C01 step 3). */
@HiltViewModel
class SaleCustomerViewModel @Inject constructor(
    private val customers: CustomerRepository,
    private val editSale: EditSale,
) : ViewModel() {

    private var query = ""
    private var search: Job? = null

    private val mutableState = MutableStateFlow<SaleCustomerUiState>(SaleCustomerUiState.Loading)

    /** The state to show. */
    val state: StateFlow<SaleCustomerUiState> = mutableState.asStateFlow()

    /** Reads the customers. Called each time the screen is shown, and to retry. */
    fun load() = refresh()

    /** @param query the new content of the search field; the list follows at once. */
    fun onQueryChange(query: String) {
        this.query = query
        (mutableState.value as? SaleCustomerUiState.Ready)?.let { mutableState.value = it.copy(query = query) }
        refresh()
    }

    /** @param customerId the customer of the sale; the counter customer is chosen by its identifier too. */
    fun choose(customerId: Long) {
        search?.cancel()
        viewModelScope.launch {
            mutableState.value = storageCall {
                val counter = customers.counter()
                editSale.setCustomer(CustomerId(customerId).takeIf { it != counter.id })
                SaleCustomerUiState.Chosen
            }.getOrElse { SaleCustomerUiState.Failed }
        }
    }

    private fun refresh() {
        search?.cancel()
        search = viewModelScope.launch {
            val asked = query
            val result = storageCall {
                SaleCustomerUiState.Ready(asked, customers.counter(), customers.search(asked, archived = false, LIMIT))
            }.getOrElse { SaleCustomerUiState.Failed }
            if (mutableState.value != SaleCustomerUiState.Chosen) {
                mutableState.value = if (result is SaleCustomerUiState.Ready) result.copy(query = query) else result
            }
        }
    }

    private companion object {
        /** Most customers listed at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}

/**
 * Choice of the customer wired to its view model.
 *
 * @param createdCustomerId the customer the user just created from this screen, or null: it is
 * chosen at once.
 * @param onClose called to leave the screen, once the customer is chosen or when the user goes back.
 * @param onNewCustomer called when the user asks to create a customer.
 * @param viewModel holder of the choice state.
 */
@Composable
fun SaleCustomerRoute(
    createdCustomerId: Long?,
    onClose: () -> Unit,
    onNewCustomer: () -> Unit,
    viewModel: SaleCustomerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(createdCustomerId) { createdCustomerId?.let(viewModel::choose) }
    if (state == SaleCustomerUiState.Chosen) {
        LaunchedEffect(Unit) { onClose() }
    }

    SaleCustomerScreen(
        state = state,
        actions = SaleCustomerActions(
            onBack = onClose,
            onNewCustomer = onNewCustomer,
            onQueryChange = viewModel::onQueryChange,
            onChoose = viewModel::choose,
            onRetry = viewModel::load,
        ),
    )
}

/**
 * What the user can do when choosing the customer of a sale.
 *
 * @property onBack called when the user goes back.
 * @property onNewCustomer called when the user asks to create a customer.
 * @property onQueryChange called with the new content of the search field.
 * @property onChoose called with the identifier of the customer the user picks.
 * @property onRetry called when the user asks to read the customers again.
 */
class SaleCustomerActions(
    val onBack: () -> Unit = {},
    val onNewCustomer: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onChoose: (Long) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The customers a sale can be made to: the counter customer first, then the search. A customer
 * that does not exist yet is created from here.
 *
 * @param state the choice state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SaleCustomerScreen(state: SaleCustomerUiState, actions: SaleCustomerActions, modifier: Modifier = Modifier) {
    TitledScreen(
        title = stringResource(R.string.sale_customer_title),
        modifier = modifier,
        onBack = actions.onBack,
        actions = { TextAction(label = stringResource(R.string.customers_new), onClick = actions.onNewCustomer) },
    ) {
        when (state) {
            SaleCustomerUiState.Loading, SaleCustomerUiState.Chosen -> LoadingState()
            SaleCustomerUiState.Failed -> ErrorState(
                message = stringResource(R.string.customers_load_error),
                onRetry = actions.onRetry,
            )
            is SaleCustomerUiState.Ready -> Customers(state, actions)
        }
    }
}

@Composable
private fun Customers(state: SaleCustomerUiState.Ready, actions: SaleCustomerActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ListMaxWidth)
            .fillMaxSize()
            .padding(horizontal = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SearchField(state.query, actions.onQueryChange, stringResource(R.string.customers_search))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            if (state.query.isBlank()) {
                item(key = state.counter.id.value) {
                    val anonymous = stringResource(R.string.customers_counter_label)
                    CustomerChoice(state.counter, anonymous, AppIcon.STORE, actions.onChoose)
                }
            }
            if (state.customers.isEmpty() && state.query.isNotBlank()) {
                item {
                    Text(
                        text = stringResource(R.string.sale_customer_no_match),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.customers, key = { it.id.value }) { customer ->
                CustomerChoice(customer, customer.summary(), AppIcon.PERSON, actions.onChoose)
            }
        }
    }
}

@Composable
private fun CustomerChoice(customer: Customer, summary: String, icon: AppIcon, onChoose: (Long) -> Unit) {
    NavigationRow(
        title = customer.details.name,
        onClick = { onChoose(customer.id.value) },
        supporting = summary,
        icon = icon,
    )
}
