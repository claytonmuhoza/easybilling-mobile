package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.runtime.Composable
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.ReadinessStep
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.groupedByDay
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.home.CatalogStatus
import com.claytonmuhoza.easybilling.home.CustomersStatus
import com.claytonmuhoza.easybilling.home.EbmsStatus
import com.claytonmuhoza.easybilling.home.HomeActions
import com.claytonmuhoza.easybilling.home.HomeScreen
import com.claytonmuhoza.easybilling.home.HomeUiState
import com.claytonmuhoza.easybilling.home.NumberingStatus
import com.claytonmuhoza.easybilling.home.ReadinessStatus
import com.claytonmuhoza.easybilling.home.SalesStatus
import com.claytonmuhoza.easybilling.home.TaxProfileStatus
import com.claytonmuhoza.easybilling.home.TaxesStatus
import com.claytonmuhoza.easybilling.invoices.InvoiceListActions
import com.claytonmuhoza.easybilling.invoices.InvoiceListScreen
import com.claytonmuhoza.easybilling.invoices.InvoiceListUiState
import com.claytonmuhoza.easybilling.navigation.MainTab
import com.claytonmuhoza.easybilling.navigation.MainTabs
import com.claytonmuhoza.easybilling.settings.SettingsActions
import com.claytonmuhoza.easybilling.settings.SettingsScreen
import com.claytonmuhoza.easybilling.settings.SettingsUiState
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * Screenshots of the home screen, of the settings and of the two main screens inside their
 * navigation, in every [ScreenshotVariant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class NavigationScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = TransmissionSamples.NOW

    private fun readiness(vararg steps: Pair<ReadinessStep, Boolean>) =
        ReadinessStatus.Known(BillingReadiness(mapOf(*steps)))

    private val nothingDone = readiness(
        ReadinessStep.TAX_PROFILE to false,
        ReadinessStep.EBMS_CONNECTION to false,
        ReadinessStep.NUMBERING to false,
    )

    private val allDone = readiness(
        ReadinessStep.TAX_PROFILE to true,
        ReadinessStep.EBMS_CONNECTION to true,
        ReadinessStep.VAT to true,
        ReadinessStep.NUMBERING to true,
    )

    private fun summary(id: Long, total: String, status: InvoiceStatus, customer: String, minutesAgo: Long) =
        InvoiceSummary(
            id = InvoiceId(id),
            type = DocumentType.FN,
            number = "FN-%05d/2026".format(id),
            issuedAt = now - Duration.ofMinutes(minutesAgo),
            customerName = customer,
            paymentType = PaymentType.CASH,
            total = Money.of(total, Currency.BIF),
            status = status,
        )

    private val recent = listOf(
        summary(48, "146566", InvoiceStatus.Registered, "Client comptoir", minutesAgo = 5),
        summary(47, "18000", InvoiceStatus.Pending, "Ets Kaneza", minutesAgo = 20),
        summary(46, "54900", InvoiceStatus.Rejected("Le NIF du contribuable est invalide."), "Inconnu SA", 110),
    )

    private val newBusiness = HomeUiState(
        readiness = nothingDone,
        catalogStatus = CatalogStatus.Counted(active = 0, withoutVat = 0),
        customersStatus = CustomersStatus.Counted(active = 0),
        salesStatus = SalesStatus.Counted(held = 0),
    )

    private val busyShop = HomeUiState(
        readiness = allDone,
        catalogStatus = CatalogStatus.Counted(active = 12, withoutVat = 0),
        customersStatus = CustomersStatus.Counted(active = 34),
        salesStatus = SalesStatus.Counted(held = 2),
        recentInvoices = recent,
    )

    private val unreadable = HomeUiState(
        readiness = ReadinessStatus.Unreadable,
        catalogStatus = CatalogStatus.Unreadable,
        customersStatus = CustomersStatus.Unreadable,
        salesStatus = SalesStatus.Unreadable,
    )

    @Composable
    private fun home(state: HomeUiState) = HomeScreen(userName = "Alice", state = state, actions = HomeActions())

    @Composable
    private fun settings(state: SettingsUiState) = SettingsScreen("Alice", state, SettingsActions())

    @Test
    fun homeNotReady() = capture("home") {
        home(newBusiness)
    }

    @Test
    fun homeReady() = capture("home_ready") {
        home(busyShop)
    }

    @Test
    fun homeLoading() = capture("home_loading") {
        home(HomeUiState())
    }

    @Test
    fun homeUnreadable() = capture("home_unreadable") {
        home(unreadable)
    }

    @Test
    fun settingsToDo() = capture("settings") {
        settings(
            SettingsUiState(
                readiness = nothingDone,
                taxProfileStatus = TaxProfileStatus.ToComplete,
                ebmsStatus = EbmsStatus.ToConfigure,
                taxesStatus = TaxesStatus.Defined(0),
                numberingStatus = NumberingStatus.ToDefine,
            ),
        )
    }

    @Test
    fun settingsReady() = capture("settings_ready") {
        settings(
            SettingsUiState(
                readiness = allDone,
                taxProfileStatus = TaxProfileStatus.Saved(2),
                ebmsStatus = EbmsStatus.Configured(EbmsEnvironment.TEST, Instant.parse("2026-10-03T12:30:00Z")),
                taxesStatus = TaxesStatus.Defined(3),
                numberingStatus = NumberingStatus.Defined("FN-00001/2026", locked = true),
            ),
        )
    }

    @Test
    fun settingsLoading() = capture("settings_loading") {
        settings(SettingsUiState())
    }

    @Test
    fun settingsUnreadable() = capture("settings_unreadable") {
        settings(
            SettingsUiState(
                readiness = ReadinessStatus.Unreadable,
                taxProfileStatus = TaxProfileStatus.Unreadable,
                ebmsStatus = EbmsStatus.Unreadable,
                taxesStatus = TaxesStatus.Unreadable,
                numberingStatus = NumberingStatus.Unreadable,
            ),
        )
    }

    @Test
    fun registerTab() = capture("main_tabs_register") {
        MainTabs(selected = MainTab.REGISTER, onSelect = {}) { home(busyShop) }
    }

    @Test
    fun invoicesTab() = capture("main_tabs_invoices") {
        MainTabs(selected = MainTab.INVOICES, onSelect = {}) {
            InvoiceListScreen(
                state = InvoiceListUiState.Ready(
                    days = recent.groupedByDay(),
                    counts = recent.groupingBy { it.status.kind }.eachCount(),
                    today = now.atZone(BusinessCalendar.ZONE).toLocalDate(),
                ),
                actions = InvoiceListActions(),
            )
        }
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
