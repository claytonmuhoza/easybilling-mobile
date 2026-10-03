package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.customers.CustomerEditorActions
import com.claytonmuhoza.easybilling.customers.CustomerEditorScreen
import com.claytonmuhoza.easybilling.customers.CustomerEditorUiState
import com.claytonmuhoza.easybilling.customers.CustomerListActions
import com.claytonmuhoza.easybilling.customers.CustomerListScreen
import com.claytonmuhoza.easybilling.customers.CustomerListUiState
import com.claytonmuhoza.easybilling.customers.TinNotice
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerForm
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerProblem
import com.claytonmuhoza.easybilling.domain.customer.TinCheckFailure
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.Test

/** Screenshots of the list of the customers and of the customer editor, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CustomerScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val counter = Customer(CustomerId(1), Customer.counterDetails(), counter = true)

    private val company = Customer(
        id = CustomerId(2),
        details = CustomerDetails(
            name = "Brasserie du Lac SA",
            origin = CustomerOrigin.LOCAL,
            personType = TaxpayerType.LEGAL_PERSON,
            tin = VerifiedTin("4000202020", "BRASSERIE DU LAC", Instant.parse("2026-10-03T08:00:00Z")),
            address = "Bujumbura, Rohero, avenue de l'Industrie",
            phone = "+257 22 22 22 22",
            vatPayer = true,
        ),
    )
    private val person = Customer(
        id = CustomerId(3),
        details = company.details.copy(
            name = "Aline Irakoze",
            personType = TaxpayerType.NATURAL_PERSON,
            tin = null,
            address = "",
            phone = "79 123 456",
            vatPayer = false,
        ),
    )
    private val foreigner = Customer(
        id = CustomerId(4),
        details = person.details.copy(
            name = "Kigali Trading Ltd",
            origin = CustomerOrigin.FOREIGN,
            personType = TaxpayerType.LEGAL_PERSON,
            address = "Kigali, Rwanda",
            phone = "",
        ),
    )
    private val file = listOf(person, company, foreigner)

    private val typed = CustomerForm(
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.LEGAL_PERSON,
        tin = "4000202020",
    )

    private fun ready(
        customers: List<Customer>,
        query: String = "",
        showArchived: Boolean = false,
        active: Int = file.size,
        archived: Int = 0,
    ) = CustomerListUiState.Ready(query, showArchived, counter, customers, active, archived)

    @Composable
    private fun editor(state: CustomerEditorUiState) = CustomerEditorScreen(state, CustomerEditorActions())

    @Test
    fun customerList() = capture("customer_list") {
        CustomerListScreen(state = ready(file), actions = CustomerListActions())
    }

    @Test
    fun customerListEmpty() = capture("customer_list_empty") {
        CustomerListScreen(state = ready(emptyList(), active = 0), actions = CustomerListActions())
    }

    @Test
    fun customerListNoMatch() = capture("customer_list_no_match") {
        CustomerListScreen(state = ready(emptyList(), query = "Ndayishimiye"), actions = CustomerListActions())
    }

    @Test
    fun customerListArchived() = capture("customer_list_archived") {
        val archived = person.copy(archived = true)
        CustomerListScreen(
            state = ready(listOf(archived), showArchived = true, archived = 1),
            actions = CustomerListActions(),
        )
    }

    @Test
    fun customerListLoading() = capture("customer_list_loading", animated = true) {
        CustomerListScreen(state = CustomerListUiState.Loading, actions = CustomerListActions())
    }

    @Test
    fun customerListError() = capture("customer_list_error") {
        CustomerListScreen(state = CustomerListUiState.Failed, actions = CustomerListActions())
    }

    @Test
    fun newCustomer() = capture("customer_new") {
        editor(CustomerEditorUiState.Editing(existing = null, form = CustomerForm()))
    }

    @Test
    fun newCustomerWithErrors() = capture("customer_new_errors") {
        editor(
            CustomerEditorUiState.Editing(
                existing = null,
                form = CustomerForm(vatPayer = true),
                problems = setOf(
                    CustomerProblem.ORIGIN_MISSING,
                    CustomerProblem.PERSON_TYPE_MISSING,
                    CustomerProblem.NAME_MISSING,
                    CustomerProblem.VAT_PAYER_WITHOUT_TIN,
                ),
                failed = true,
            ),
        )
    }

    @Test
    fun newCustomerCheckingTin() = capture("customer_checking_tin") {
        editor(CustomerEditorUiState.Editing(existing = null, form = typed, busy = true, checkingTin = true))
    }

    @Test
    fun newCustomerTinRecognised() = capture("customer_tin_recognised") {
        editor(
            CustomerEditorUiState.Editing(
                existing = null,
                form = typed.copy(name = "BRASSERIE DU LAC"),
                tinNotice = TinNotice.Recognised("BRASSERIE DU LAC"),
            ),
        )
    }

    @Test
    fun newCustomerTinUnknown() = capture("customer_tin_unknown") {
        val unknown = typed.copy(tin = "999")
        editor(CustomerEditorUiState.Editing(existing = null, form = unknown, tinNotice = TinNotice.Unknown))
    }

    @Test
    fun newCustomerObrUnreachable() = capture("customer_obr_unreachable") {
        editor(
            CustomerEditorUiState.Editing(
                existing = null,
                form = typed.copy(name = "Brasserie du Lac SA"),
                tinNotice = TinNotice.NotChecked(TinCheckFailure.UNREACHABLE, ""),
            ),
        )
    }

    @Test
    fun newCustomerTinTaken() = capture("customer_tin_taken") {
        editor(
            CustomerEditorUiState.Editing(
                existing = null,
                form = typed.copy(name = "Brasserie du Lac SA"),
                problems = setOf(CustomerProblem.TIN_TAKEN),
            ),
        )
    }

    @Test
    fun existingCustomer() = capture("customer_existing") {
        editor(CustomerEditorUiState.Editing(existing = company, form = CustomerForm.from(company)))
    }

    @Test
    fun foreignCustomerArchived() = capture("customer_foreign_archived") {
        val archived = foreigner.copy(archived = true)
        editor(CustomerEditorUiState.Editing(existing = archived, form = CustomerForm.from(archived)))
    }

    @Test
    fun counterCustomer() = capture("customer_counter") {
        editor(CustomerEditorUiState.Editing(existing = counter, form = CustomerForm.from(counter)))
    }

    @Test
    fun customerLoadError() = capture("customer_load_error") {
        editor(CustomerEditorUiState.Failed)
    }

    @Test
    fun customerNotFound() = capture("customer_not_found") {
        editor(CustomerEditorUiState.NotFound)
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
