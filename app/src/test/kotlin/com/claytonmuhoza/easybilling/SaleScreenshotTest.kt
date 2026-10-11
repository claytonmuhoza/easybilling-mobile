package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountKind
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.DraftLine
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceTotals
import com.claytonmuhoza.easybilling.domain.invoicing.LineInput
import com.claytonmuhoza.easybilling.domain.invoicing.ObrLineTaxCalculator
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.PricedLine
import com.claytonmuhoza.easybilling.domain.invoicing.PricedSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.SaleProblem
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.ObrRoundingPolicy
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.sale.HeldSalesActions
import com.claytonmuhoza.easybilling.sale.HeldSalesScreen
import com.claytonmuhoza.easybilling.sale.HeldSalesUiState
import com.claytonmuhoza.easybilling.sale.InvoiceIssuedActions
import com.claytonmuhoza.easybilling.sale.InvoiceIssuedScreen
import com.claytonmuhoza.easybilling.sale.InvoiceIssuedUiState
import com.claytonmuhoza.easybilling.sale.IssueRefusal
import com.claytonmuhoza.easybilling.sale.LineEditor
import com.claytonmuhoza.easybilling.sale.SaleActions
import com.claytonmuhoza.easybilling.sale.SaleCustomerActions
import com.claytonmuhoza.easybilling.sale.SaleCustomerScreen
import com.claytonmuhoza.easybilling.sale.SaleCustomerUiState
import com.claytonmuhoza.easybilling.sale.SalePane
import com.claytonmuhoza.easybilling.sale.SaleScreen
import com.claytonmuhoza.easybilling.sale.SaleUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test

/**
 * Screenshots of the cash register, of the choice of the customer, of the sales on hold and of
 * the issued invoice, in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SaleScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val registrations = TaxRegistrations(vat = true)
    private val vat = TaxComputation.Percentage(BigDecimal("18"), PercentageBase.PRICE_WITHOUT_VAT)
    private val calculator = ObrLineTaxCalculator()

    private fun item(id: Long, designation: String, price: String, mode: PriceMode = PriceMode.EXCLUDING_TAXES) = Item(
        id = ItemId(id),
        code = "ART-%04d".format(id),
        details = ItemDetails(
            designation = designation,
            unit = "pièce",
            price = Money.of(price, Currency.BIF),
            priceMode = mode,
            taxes = mapOf(TaxKind.VAT to TaxId(1)),
            stockTracked = true,
        ),
    )

    private val soap = item(1, "Savon de Marseille", "1000")
    private val sugar = item(2, "Sucre 1 kg", "2360", PriceMode.INCLUDING_TAXES)
    private val coffee = item(3, "Café moulu 250 g", "8500.5")
    private val oil = item(4, "Huile de palme 1 l", "4200")
    private val catalogue = listOf(coffee, oil, soap, sugar)

    private fun line(
        item: Item,
        quantity: String,
        discount: Discount? = null,
        problems: Set<SaleProblem> = emptySet(),
    ): PricedLine {
        val net = discount?.applyTo(item.details.price, ObrRoundingPolicy) ?: item.details.price
        val computations = mapOf(TaxKind.VAT to vat)
        val input = LineInput(Quantity.of(quantity), net, item.details.priceMode, computations)
        val draft = DraftLine(item.id, Quantity.of(quantity), discount)
        return PricedLine(draft, item, net, computations, calculator.compute(input, registrations), problems)
    }

    private val counter = Customer(CustomerId(1), Customer.counterDetails(), counter = true)
    private val company = Customer(
        id = CustomerId(2),
        details = CustomerDetails(
            name = "Brasserie du Lac SA",
            origin = CustomerOrigin.LOCAL,
            personType = TaxpayerType.LEGAL_PERSON,
            tin = VerifiedTin("4000202020", "BRASSERIE DU LAC", now),
            address = "Bujumbura, Rohero",
            phone = "+257 22 22 22 22",
            vatPayer = true,
        ),
    )
    private val person = Customer(CustomerId(3), Customer.counterDetails("Aline Irakoze"))

    private fun sale(
        lines: List<PricedLine>,
        customer: Customer = counter,
        payment: PaymentType = PaymentType.CASH,
        problems: Set<SaleProblem> = emptySet(),
        id: Long = 1,
    ) = PricedSale(
        draft = SaleDraft(
            id = DraftId(id),
            customerId = customer.id.takeIf { !customer.counter },
            paymentType = payment,
            lines = lines.map { it.line },
            updatedAt = now,
        ),
        customer = customer,
        registrations = registrations,
        lines = lines,
        totals = InvoiceTotals.of(lines.mapNotNull { it.amounts }),
        problems = problems,
    )

    private val typed = sale(
        lines = listOf(line(soap, "2", Discount.Percent(BigDecimal("10"))), line(sugar, "1.5")),
        customer = company,
        payment = PaymentType.BANK,
    )

    private fun ready(sale: PricedSale = typed, pane: SalePane = SalePane.CART) =
        SaleUiState.Ready(sale = sale, results = catalogue, heldCount = 2, pane = pane)

    @Composable
    private fun register(state: SaleUiState) = SaleScreen(state, SaleActions())

    @Test
    fun saleCatalogue() = capture("sale_catalogue") {
        register(ready(pane = SalePane.CATALOGUE))
    }

    @Test
    fun saleCatalogueNoMatch() = capture("sale_catalogue_no_match") {
        val state = ready(pane = SalePane.CATALOGUE).copy(query = "thé vert", results = emptyList(), heldCount = 0)
        register(state)
    }

    @Test
    fun saleCart() = capture("sale_cart") {
        register(ready())
    }

    @Test
    fun saleCartEmpty() = capture("sale_cart_empty") {
        val empty = sale(emptyList(), problems = setOf(SaleProblem.EMPTY))
        register(SaleUiState.Ready(sale = empty, results = catalogue, pane = SalePane.CART))
    }

    @Test
    fun saleLineEditor() = capture("sale_line_editor") {
        val editor = LineEditor(soap.id, SaleLineForm("2", DiscountKind.PERCENT, "10"))
        register(ready().copy(editor = editor))
    }

    @Test
    fun saleLineEditorErrors() = capture("sale_line_editor_errors") {
        val editor = LineEditor(
            itemId = soap.id,
            form = SaleLineForm("0", DiscountKind.PER_UNIT, ""),
            problems = setOf(SaleLineProblem.QUANTITY_INVALID, SaleLineProblem.DISCOUNT_INVALID),
        )
        register(ready().copy(editor = editor))
    }

    @Test
    fun saleBlocked() = capture("sale_blocked") {
        val blocked = sale(
            lines = listOf(line(soap, "1", problems = setOf(SaleProblem.ITEM_WITHOUT_VAT))),
            customer = person,
            problems = setOf(SaleProblem.CUSTOMER_ARCHIVED),
        )
        register(ready(blocked).copy(refusal = IssueRefusal.NUMBERS_EXHAUSTED, failed = true))
    }

    @Test
    fun saleStock() = capture("sale_stock") {
        val short = setOf(SaleProblem.STOCK_INSUFFICIENT)
        val lines = listOf(
            line(soap, "2").copy(available = BigDecimal("148")),
            line(oil, "3", problems = short).copy(available = BigDecimal("1.5")),
            line(coffee, "1", problems = short).copy(available = BigDecimal.ZERO),
        )
        register(ready(sale(lines)))
    }

    @Test
    fun saleNotReady() = capture("sale_not_ready") {
        val readiness = BillingReadiness(mapOf(ReadinessStep.TAX_PROFILE to true, ReadinessStep.NUMBERING to false))
        register(SaleUiState.NotReady(readiness))
    }

    @Test
    fun saleLoading() = capture("sale_loading", animated = true) {
        register(SaleUiState.Loading)
    }

    @Test
    fun saleError() = capture("sale_error") {
        register(SaleUiState.Failed)
    }

    @Test
    fun saleCustomer() = capture("sale_customer") {
        val state = SaleCustomerUiState.Ready("", counter, listOf(person, company))
        SaleCustomerScreen(state, SaleCustomerActions())
    }

    @Test
    fun saleCustomerNoMatch() = capture("sale_customer_no_match") {
        val state = SaleCustomerUiState.Ready("Ndayishimiye", counter, emptyList())
        SaleCustomerScreen(state, SaleCustomerActions())
    }

    @Test
    fun saleCustomerError() = capture("sale_customer_error") {
        SaleCustomerScreen(SaleCustomerUiState.Failed, SaleCustomerActions())
    }

    @Test
    fun heldSales() = capture("held_sales") {
        val other = sale(listOf(line(coffee, "1")), id = 2)
        HeldSalesScreen(HeldSalesUiState.Ready(listOf(typed, other)), HeldSalesActions())
    }

    @Test
    fun heldSalesEmpty() = capture("held_sales_empty") {
        HeldSalesScreen(HeldSalesUiState.Ready(emptyList()), HeldSalesActions())
    }

    @Test
    fun heldSalesError() = capture("held_sales_error") {
        HeldSalesScreen(HeldSalesUiState.Failed, HeldSalesActions())
    }

    private fun issuedInvoice(): Invoice {
        val content = InvoiceContent(
            type = DocumentType.FN,
            number = "FN-00042/2026",
            identifier = "4000000001/ws400000000100001/20261003101542/FN-00042/2026",
            issuedAt = now,
            profileVersion = 1,
            environment = EbmsEnvironment.TEST,
            customer = InvoiceCustomer(company.id, company.details.name, "4000202020", "Bujumbura, Rohero", true),
            paymentType = PaymentType.BANK,
            currency = Currency.BIF,
            lines = typed.lines.map {
                InvoiceLine(
                    itemId = it.item.id,
                    itemCode = it.item.code,
                    designation = it.item.details.designation,
                    unit = it.item.details.unit,
                    stockTracked = true,
                    quantity = it.line.quantity,
                    listUnitPrice = it.item.details.price,
                    priceMode = it.item.details.priceMode,
                    discount = it.line.discount,
                    vatRate = BigDecimal("18"),
                    amounts = it.amounts!!,
                )
            },
            issuedBy = UserId(1),
        )
        return Invoice(InvoiceId(42), content)
    }

    @Test
    fun invoiceIssued() = capture("invoice_issued") {
        val state = InvoiceIssuedUiState.Ready(issuedInvoice(), InvoiceStatus.Pending)
        InvoiceIssuedScreen(state, InvoiceIssuedActions())
    }

    @Test
    fun invoiceRegistered() = capture("invoice_registered") {
        val receipt = ObrReceipt("4530253", "2026-10-03 10:15:44", "9a2a3f27dd833f6f4a069235ce165723be1b7340", "{}")
        val state = InvoiceIssuedUiState.Ready(issuedInvoice(), InvoiceStatus.Registered, receipt)
        InvoiceIssuedScreen(state, InvoiceIssuedActions())
    }

    @Test
    fun invoiceRejected() = capture("invoice_rejected") {
        val refusal = "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide"
        val state = InvoiceIssuedUiState.Ready(issuedInvoice(), InvoiceStatus.Rejected(refusal))
        InvoiceIssuedScreen(state, InvoiceIssuedActions())
    }

    @Test
    fun invoiceNotFound() = capture("invoice_not_found") {
        InvoiceIssuedScreen(InvoiceIssuedUiState.NotFound, InvoiceIssuedActions())
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
