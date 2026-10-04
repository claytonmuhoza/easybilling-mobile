package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.testing.rateAt
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.configuration.CreateTax
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxMode
import com.claytonmuhoza.easybilling.domain.configuration.TaxRateForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.testing.TestClock
import com.claytonmuhoza.easybilling.testing.onAllNodesWithTextCount
import com.claytonmuhoza.easybilling.testing.pressInForm
import com.claytonmuhoza.easybilling.testing.pressOnHome
import com.claytonmuhoza.easybilling.testing.replaceInForm
import com.claytonmuhoza.easybilling.testing.typeInForm
import com.claytonmuhoza.easybilling.testing.waitForText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Taxes and line preview (UC-A07), by the administrator "Alice", already logged in. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en")
class TaxesFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var createAdministrator: CreateAdministrator

    @Inject
    lateinit var sessions: SessionManager

    @Inject
    lateinit var taxes: TaxRepository

    @Inject
    lateinit var createTax: CreateTax

    @Inject
    lateinit var auditStore: AuditStore

    @Inject
    lateinit var clock: TestClock

    private lateinit var alice: User
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** The day of the test clock in Bujumbura. */
    private val today = LocalDate.of(2026, 10, 2)

    @Before
    fun logInAlice() {
        hiltRule.inject()
        runBlocking {
            alice = assertIs<CreateAdministrator.Result.Created>(createAdministrator("Alice", "123456", "123456")).user
            sessions.open(alice)
        }
    }

    @After
    fun closeActivity() = scenario.close()

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun define(kind: TaxKind, name: String, mode: TaxMode, value: String = "") = runBlocking {
        val form = TaxForm(kind, name, TaxRateForm(mode, value, validFrom = today))
        assertIs<CreateTax.Result.Created>(createTax(form, alice.id)).tax
    }

    private fun openTaxes() {
        composeRule.pressOnHome("Taxes")
        composeRule.waitForText("Line preview")
    }

    @Test
    fun `the home screen shows that no tax exists yet`() {
        launch()

        composeRule.waitForText("No tax").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a VAT is created with its first rate and listed`() {
        launch()
        openTaxes()
        composeRule.waitForText("No tax yet")

        composeRule.onNodeWithText("New tax").performClick()
        composeRule.waitForText("Create the tax")
        composeRule.onNodeWithText("VAT").performClick()
        composeRule.typeInForm("Name", "TVA 18 %")
        composeRule.typeInForm("Rate in percent", "18")
        composeRule.pressInForm("Create the tax")

        // The name is also in the field of the editor: wait for the rate, only shown by the list.
        composeRule.waitForText("18 %")
        composeRule.onNodeWithText("TVA 18 %").assertIsDisplayed()
        composeRule.onNodeWithText("18 %").assertIsDisplayed()
        val tax = runBlocking { taxes.all() }.single()
        assertEquals(TaxKind.VAT, tax.kind)
        assertEquals(today, tax.latest.validFrom)
        assertEquals("TAX_CREATED", runBlocking { auditStore.readAll().last().action })
    }

    @Test
    fun `the VAT can only be a percentage`() {
        launch()
        openTaxes()
        composeRule.waitForText("No tax yet")
        composeRule.onNodeWithText("New tax").performClick()
        composeRule.waitForText("Create the tax")

        composeRule.onNodeWithText("VAT").performClick()

        composeRule.waitForText("The VAT is always a percent of the price without VAT.").assertIsDisplayed()
        composeRule.onNodeWithText("Per unit").assertDoesNotExist()
    }

    @Test
    fun `a rate above 100 percent or starting in the past is refused`() {
        launch()
        openTaxes()
        composeRule.waitForText("No tax yet")
        composeRule.onNodeWithText("New tax").performClick()
        composeRule.waitForText("Create the tax")
        composeRule.onNodeWithText("TC").performClick()
        composeRule.typeInForm("Name", "TC")
        composeRule.pressInForm("Percent")
        composeRule.typeInForm("Rate in percent", "120")
        composeRule.replaceInForm("Applies from (DD/MM/YYYY)", "01/01/2020")

        composeRule.pressInForm("Create the tax")

        composeRule.waitForText("At most 100 %").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("A rate never starts in the past").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList(), runBlocking { taxes.all() })
    }

    @Test
    fun `a new rate is scheduled from a later day and the current one stays in force`() {
        define(TaxKind.VAT, "TVA", TaxMode.PERCENTAGE, "18")
        launch()
        openTaxes()
        composeRule.waitForText("TVA").performClick()
        composeRule.waitForText("Schedule this rate")

        composeRule.typeInForm("Rate in percent", "16")
        composeRule.typeInForm("Applies from (DD/MM/YYYY)", "01/01/2027")
        composeRule.pressInForm("Schedule this rate")

        composeRule.waitForText("Rate scheduled.").performScrollTo().assertIsDisplayed()
        val tax = runBlocking { taxes.all() }.single()
        assertEquals(BigDecimal("18"), tax.rateAt(today))
        assertEquals(BigDecimal("16"), tax.rateAt(LocalDate.of(2027, 1, 1)))
        assertEquals(1, composeRule.onAllNodesWithTextCount("upcoming", substring = true))
    }

    @Test
    fun `an archived tax stays listed as archived`() {
        define(TaxKind.OTT, "OTT", TaxMode.FREE_AMOUNT)
        launch()
        openTaxes()
        composeRule.waitForText("OTT", substring = false).performClick()
        composeRule.waitForText("Archive the tax")

        composeRule.pressInForm("Archive the tax")

        composeRule.waitForText("Use the tax again").assertIsDisplayed()
        assertEquals(true, runBlocking { taxes.all() }.single().archived)
    }

    @Test
    fun `the preview computes the second example of the specification`() {
        define(TaxKind.VAT, "TVA 18 %", TaxMode.PERCENTAGE, "18")
        define(TaxKind.CONSUMPTION_TAX, "TC libre", TaxMode.FREE_AMOUNT)
        define(TaxKind.FLAT_RATE_WITHHOLDING, "PFL libre", TaxMode.FREE_AMOUNT)
        launch()
        openTaxes()
        composeRule.onNodeWithText("Line preview").performClick()
        composeRule.waitForText("Unit price")

        composeRule.typeInForm("Unit price", "200")
        composeRule.replaceInForm("Quantity", "10")
        composeRule.pressInForm("TVA 18 %")
        composeRule.pressInForm("TC libre")
        composeRule.typeInForm("TC amount of the line", "30")
        composeRule.pressInForm("PFL libre")
        composeRule.typeInForm("PFL amount of the line", "25")

        composeRule.onNodeWithText("2,030 BIF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("365.4 BIF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2,395.4 BIF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2,420.4 BIF").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a price including taxes keeps its total and finds the price without taxes`() {
        define(TaxKind.VAT, "TVA 18 %", TaxMode.PERCENTAGE, "18")
        launch()
        openTaxes()
        composeRule.onNodeWithText("Line preview").performClick()
        composeRule.waitForText("Unit price")

        composeRule.typeInForm("Unit price", "590")
        composeRule.replaceInForm("Quantity", "10")
        composeRule.pressInForm("Includes taxes")
        composeRule.pressInForm("TVA 18 %")

        composeRule.onNodeWithText("500 BIF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("900 BIF").performScrollTo().assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithTextCount("5,900 BIF"))
    }

    @Test
    fun `without tax profile the preview applies every tax and says so`() {
        launch()
        openTaxes()
        composeRule.onNodeWithText("Line preview").performClick()

        composeRule.waitForText("No tax profile yet: every tax is applied.").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the taxes are translated in French`() {
        launch()
        composeRule.pressOnHome("Taxes", banner = "Synchronisé avec l'OBR")

        composeRule.waitForText("Aucune taxe").assertIsDisplayed()
        composeRule.onNodeWithText("Nouvelle taxe").assertIsDisplayed()
        composeRule.onNodeWithText("Aperçu d'une ligne").assertIsDisplayed()
    }
}
