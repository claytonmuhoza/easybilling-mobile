package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.configuration.EbmsConnectionForm
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialField
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsServerAddress
import com.claytonmuhoza.easybilling.domain.configuration.FieldProblem
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileChoice
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.configuration.UrlProblem
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.ebms.EbmsConnectionActions
import com.claytonmuhoza.easybilling.ebms.EbmsConnectionScreen
import com.claytonmuhoza.easybilling.ebms.EbmsConnectionUiState
import com.claytonmuhoza.easybilling.ebms.ExchangeLogActions
import com.claytonmuhoza.easybilling.ebms.ExchangeLogScreen
import com.claytonmuhoza.easybilling.ebms.ExchangeLogUiState
import com.claytonmuhoza.easybilling.ebms.ExchangeScreen
import com.claytonmuhoza.easybilling.ebms.ExchangeUiState
import com.claytonmuhoza.easybilling.ebms.PinPrompt
import com.claytonmuhoza.easybilling.home.EbmsStatus
import com.claytonmuhoza.easybilling.home.HomeActions
import com.claytonmuhoza.easybilling.home.HomeScreen
import com.claytonmuhoza.easybilling.home.TaxProfileStatus
import com.claytonmuhoza.easybilling.login.LoginScreen
import com.claytonmuhoza.easybilling.login.LoginUiState
import com.claytonmuhoza.easybilling.recovery.RecoveryCodeError
import com.claytonmuhoza.easybilling.recovery.RecoveryCodeScreen
import com.claytonmuhoza.easybilling.recovery.ResetPinScreen
import com.claytonmuhoza.easybilling.recovery.ResetPinUiState
import com.claytonmuhoza.easybilling.setup.NameError
import com.claytonmuhoza.easybilling.setup.SetupScreen
import com.claytonmuhoza.easybilling.setup.SetupUiState
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileActions
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileHistoryScreen
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileHistoryUiState
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileScreen
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileUiState
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileVersionScreen
import com.claytonmuhoza.easybilling.taxprofile.TaxProfileVersionUiState
import com.claytonmuhoza.easybilling.taxprofile.VersionSummary
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.Test

/**
 * Screenshots of the first launch, login, home and tax profile screens in every supported language,
 * theme and screen size. The screens are rendered from a fixed state, without view model nor database.
 *
 * References live in `src/test/screenshots`. Record them with `./gradlew recordRoborazziDebug`.
 *
 * The emulated Android version is pinned for the reason given in the design system screenshot
 * test: on Android 17 Robolectric renders asynchronously and the capture sometimes comes out blank.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ScreensScreenshotTest(private val variant: Variant) {

    private val composeRule = createComposeRule()

    /** The qualifiers must be set before the compose rule creates its activity. */
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() = RuntimeEnvironment.setQualifiers(variant.qualifiers)
            },
        )
        .around(composeRule)

    @Test
    fun setupForm() = capture("setup_form") {
        SetupScreen(
            state = SetupUiState.Form(),
            onNameChange = {},
            onPinChange = {},
            onConfirmationChange = {},
            onSubmit = {},
        )
    }

    @Test
    fun setupFormWithErrors() = capture("setup_form_errors") {
        SetupScreen(
            state = SetupUiState.Form(
                pin = "12345",
                confirmation = "12345",
                nameError = NameError.BLANK,
                pinError = PinProblem.TOO_SHORT,
            ),
            onNameChange = {},
            onPinChange = {},
            onConfirmationChange = {},
            onSubmit = {},
        )
    }

    @Test
    fun recoveryCode() = capture("recovery_code") {
        RecoveryCodeScreen(code = "ABCD-EFGH-JKLM", onAcknowledge = {})
    }

    @Test
    fun loginLoading() = capture("login_loading", animated = true) {
        LoginScreen(state = LoginUiState.Loading, onPinChange = {}, onSubmit = {}, onForgotPin = {})
    }

    @Test
    fun login() = capture("login") {
        LoginScreen(
            state = LoginUiState.Ready(userName = "Alice"),
            onPinChange = {},
            onSubmit = {},
            onForgotPin = {},
        )
    }

    @Test
    fun loginWrongPin() = capture("login_wrong_pin") {
        LoginScreen(
            state = LoginUiState.Ready(userName = "Alice", remainingAttempts = 9),
            onPinChange = {},
            onSubmit = {},
            onForgotPin = {},
        )
    }

    @Test
    fun loginLocked() = capture("login_locked") {
        LoginScreen(
            state = LoginUiState.Ready(userName = "Alice", lockSecondsRemaining = 60),
            onPinChange = {},
            onSubmit = {},
            onForgotPin = {},
        )
    }

    @Test
    fun resetPinForm() = capture("reset_pin_form") {
        ResetPinScreen(
            state = ResetPinUiState.Form(),
            onRecoveryCodeChange = {},
            onPinChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBack = {},
        )
    }

    @Test
    fun resetPinWrongCode() = capture("reset_pin_wrong_code") {
        ResetPinScreen(
            state = ResetPinUiState.Form(
                recoveryCode = "ABCD-EFGH-JKLM",
                pin = "987654",
                confirmation = "987654",
                codeError = RecoveryCodeError.Wrong(remainingAttempts = 9),
            ),
            onRecoveryCodeChange = {},
            onPinChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBack = {},
        )
    }

    @Test
    fun homeProfileToComplete() = capture("home") {
        HomeScreen(
            userName = "Alice",
            taxProfileStatus = TaxProfileStatus.ToComplete,
            ebmsStatus = EbmsStatus.ToConfigure,
            actions = HomeActions(),
        )
    }

    @Test
    fun homeProfileSaved() = capture("home_profile_saved") {
        HomeScreen(
            userName = "Alice",
            taxProfileStatus = TaxProfileStatus.Saved(2),
            ebmsStatus = EbmsStatus.Configured(EbmsEnvironment.TEST, Instant.parse("2026-10-03T12:30:00Z")),
            actions = HomeActions(),
        )
    }

    @Test
    fun taxProfileLoading() = capture("tax_profile_loading", animated = true) {
        TaxProfileScreen(state = TaxProfileUiState.Loading, actions = TaxProfileActions())
    }

    @Test
    fun taxProfileLoadError() = capture("tax_profile_load_error") {
        TaxProfileScreen(state = TaxProfileUiState.LoadFailed, actions = TaxProfileActions())
    }

    @Test
    fun taxProfileEmpty() = capture("tax_profile_empty") {
        TaxProfileScreen(state = TaxProfileUiState.Editing(TaxProfileForm()), actions = TaxProfileActions())
    }

    @Test
    fun taxProfileFilled() = capture("tax_profile_filled") {
        TaxProfileScreen(state = TaxProfileUiState.Editing(sampleForm()), actions = TaxProfileActions())
    }

    @Test
    fun taxProfileErrors() = capture("tax_profile_errors") {
        val form = sampleForm().with(TaxProfileField.NAME, "").with(TaxProfileField.TIN, "1234567890123456")
        val invalid = form.check() as TaxProfileCheck.Invalid
        TaxProfileScreen(
            state = TaxProfileUiState.Editing(
                form = form.copy(taxpayerType = null),
                problems = invalid.problems,
                missingChoices = setOf(TaxProfileChoice.TAXPAYER_TYPE),
                saveFailed = true,
            ),
            actions = TaxProfileActions(),
        )
    }

    @Test
    fun taxProfileHistory() = capture("tax_profile_history") {
        TaxProfileHistoryScreen(
            state = TaxProfileHistoryUiState.Ready(
                listOf(
                    VersionSummary(2, Instant.parse("2026-10-03T12:30:00Z"), "Alice"),
                    VersionSummary(1, Instant.parse("2026-09-28T07:05:00Z"), null),
                ),
            ),
            onBack = {},
            onOpenVersion = {},
            onRetry = {},
        )
    }

    @Test
    fun taxProfileHistoryEmpty() = capture("tax_profile_history_empty") {
        TaxProfileHistoryScreen(
            state = TaxProfileHistoryUiState.Ready(emptyList()),
            onBack = {},
            onOpenVersion = {},
            onRetry = {},
        )
    }

    @Test
    fun taxProfileVersion() = capture("tax_profile_version") {
        val profile = (sampleForm().check() as TaxProfileCheck.Valid).profile
        TaxProfileVersionScreen(
            number = 1,
            state = TaxProfileVersionUiState.Ready(
                TaxProfileVersion(1, profile, Instant.parse("2026-09-28T07:05:00Z"), UserId(1)),
                authorName = "Alice",
            ),
            onBack = {},
            onRetry = {},
        )
    }

    @Test
    fun taxProfileVersionNotFound() = capture("tax_profile_version_not_found") {
        TaxProfileVersionScreen(number = 9, state = TaxProfileVersionUiState.NotFound, onBack = {}, onRetry = {})
    }

    @Test
    fun ebmsConnectionEmpty() = capture("ebms_connection_empty") {
        EbmsConnectionScreen(
            state = EbmsConnectionUiState.Ready(
                activeEnvironment = EbmsEnvironment.TEST,
                form = EbmsConnectionForm(serverUrl = EbmsServerAddress.DEFAULT_TEST),
                hasStoredPassword = false,
            ),
            actions = EbmsConnectionActions(),
        )
    }

    @Test
    fun ebmsConnectionTested() = capture("ebms_connection_tested") {
        EbmsConnectionScreen(
            state = EbmsConnectionUiState.Ready(
                activeEnvironment = EbmsEnvironment.TEST,
                form = sampleConnection(),
                hasStoredPassword = true,
                lastSuccess = Instant.parse("2026-10-03T12:30:00Z"),
                testOutcome = LoginOutcome.Success("token"),
            ),
            actions = EbmsConnectionActions(),
        )
    }

    @Test
    fun ebmsConnectionErrors() = capture("ebms_connection_errors") {
        EbmsConnectionScreen(
            state = EbmsConnectionUiState.Ready(
                activeEnvironment = EbmsEnvironment.PRODUCTION,
                form = sampleConnection().copy(serverUrl = "http://ebms.example.bi/api/", username = ""),
                hasStoredPassword = false,
                problems = mapOf(
                    EbmsCredentialField.USERNAME to FieldProblem.Missing,
                    EbmsCredentialField.PASSWORD to FieldProblem.Missing,
                ),
                urlProblem = UrlProblem.NOT_HTTPS,
                testOutcome = LoginOutcome.WrongCredentials,
            ),
            actions = EbmsConnectionActions(),
        )
    }

    @Test
    fun ebmsConnectionLoading() = capture("ebms_connection_loading", animated = true) {
        EbmsConnectionScreen(state = EbmsConnectionUiState.Loading, actions = EbmsConnectionActions())
    }

    @Test
    fun ebmsConnectionLoadError() = capture("ebms_connection_load_error") {
        EbmsConnectionScreen(state = EbmsConnectionUiState.LoadFailed, actions = EbmsConnectionActions())
    }

    @Test
    fun ebmsProductionPin() = capture("ebms_production_pin") {
        EbmsConnectionScreen(
            state = EbmsConnectionUiState.Ready(
                activeEnvironment = EbmsEnvironment.TEST,
                form = sampleConnection(),
                hasStoredPassword = true,
                pinPrompt = PinPrompt(pin = "000000", remainingAttempts = 9),
            ),
            actions = EbmsConnectionActions(),
        )
    }

    @Test
    fun exchangeLog() = capture("exchange_log") {
        ExchangeLogScreen(
            state = ExchangeLogUiState.Ready(
                exchanges = listOf(
                    sampleExchange(id = 2, minutesAgo = 5, statusCode = 200),
                    sampleExchange(id = 1, minutesAgo = 65, statusCode = null),
                ),
                retentionDays = 90,
            ),
            actions = ExchangeLogActions(),
        )
    }

    @Test
    fun exchangeLogEmpty() = capture("exchange_log_empty") {
        ExchangeLogScreen(
            state = ExchangeLogUiState.Ready(emptyList(), retentionDays = 90),
            actions = ExchangeLogActions(),
        )
    }

    @Test
    fun exchangeDetail() = capture("exchange_detail") {
        ExchangeScreen(
            state = ExchangeUiState.Ready(sampleExchange(id = 2, minutesAgo = 5, statusCode = 200)),
            onBack = {},
            onRetry = {},
        )
    }

    /** Connection settings made up for the screenshots; the password field is empty, as on screen. */
    private fun sampleConnection() = EbmsConnectionForm(
        serverUrl = EbmsServerAddress.DEFAULT_TEST,
        username = "ws400000000100001",
    )

    /** An exchange made up for the screenshots, secrets already masked. */
    private fun sampleExchange(id: Long, minutesAgo: Long, statusCode: Int?) = ObrExchange(
        id = id,
        startedAt = Instant.parse("2026-10-03T12:30:00Z").minusSeconds(minutesAgo * 60),
        environment = EbmsEnvironment.TEST,
        service = "login/",
        requestBody = """{"username":"ws400000000100001","password":"••••"}""",
        statusCode = statusCode,
        responseBody = if (statusCode == null) "" else """{"success":true,"result":{"token":"••••"}}""",
        durationMillis = if (statusCode == null) 15_000 else 412,
        failure = if (statusCode == null) "SocketTimeoutException: timeout" else "",
    )

    /** A complete profile, made up for the screenshots. */
    private fun sampleForm() = TaxProfileForm(
        taxpayerType = TaxpayerType.LEGAL_PERSON,
        texts = mapOf(
            TaxProfileField.NAME to "Boutique Umuco SPRL",
            TaxProfileField.TIN to "4000000001",
            TaxProfileField.TRADE_NUMBER to "RC 12345",
            TaxProfileField.PHONE_NUMBER to "+257 22 22 22 22",
            TaxProfileField.PROVINCE to "Bujumbura Mairie",
            TaxProfileField.COMMUNE to "Mukaza",
            TaxProfileField.QUARTIER to "Rohero",
            TaxProfileField.AVENUE to "Avenue de l'Industrie",
            TaxProfileField.ADDRESS_NUMBER to "12",
            TaxProfileField.ACTIVITY_SECTOR to "Commerce general",
            TaxProfileField.LEGAL_FORM to "SPRL",
        ),
        taxes = TaxRegistrations(vat = true),
        fiscalCenter = FiscalCenter.DMC,
    )

    /**
     * Renders [content] and compares it with (or records) its reference image.
     *
     * @param animated true for content that animates forever: the clock is then driven by hand to
     * a fixed instant, so the captured frame is always the same.
     */
    private fun capture(
        name: String,
        animated: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.mainClock.autoAdvance = !animated
        composeRule.setContent {
            // The surface is the one the application root puts behind every screen.
            EasyBillingTheme {
                Surface { content() }
            }
        }
        if (animated) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(ANIMATION_INSTANT_MILLIS)
        }
        composeRule.waitForIdle()
        val path = "src/test/screenshots/${name}_${variant.name}.png"
        composeRule.onRoot().captureRoboImage(path)
    }

    /**
     * One rendering configuration.
     *
     * @property name suffix of the screenshot file.
     * @property qualifiers Robolectric resource qualifiers: language, screen size, theme, density.
     */
    data class Variant(val name: String, val qualifiers: String) {
        override fun toString() = name
    }

    companion object {
        /** Instant of an endless animation at which its frame is captured. */
        private const val ANIMATION_INSTANT_MILLIS = 500L

        /** Device name, screen size qualifier, density qualifier. */
        private val devices = listOf(
            Triple("phone", "w360dp-h640dp", "xhdpi"),
            Triple("tablet", "w800dp-h1280dp", "mdpi"),
        )
        private val themes = mapOf("light" to "notnight", "dark" to "night")
        private val languages = listOf("en", "fr")

        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Variant> = devices.flatMap { (deviceName, size, density) ->
            themes.flatMap { (themeName, night) ->
                languages.map { language ->
                    Variant(
                        name = "${deviceName}_${themeName}_$language",
                        qualifiers = "$language-$size-$night-$density",
                    )
                }
            }
        }
    }
}
