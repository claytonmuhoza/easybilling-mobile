package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.home.HomeScreen
import com.claytonmuhoza.easybilling.login.LoginScreen
import com.claytonmuhoza.easybilling.login.LoginUiState
import com.claytonmuhoza.easybilling.recovery.RecoveryCodeError
import com.claytonmuhoza.easybilling.recovery.RecoveryCodeScreen
import com.claytonmuhoza.easybilling.recovery.ResetPinScreen
import com.claytonmuhoza.easybilling.recovery.ResetPinUiState
import com.claytonmuhoza.easybilling.setup.NameError
import com.claytonmuhoza.easybilling.setup.SetupScreen
import com.claytonmuhoza.easybilling.setup.SetupUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * Screenshots of the first launch, login and home screens in every supported language, theme and
 * screen size. The screens are rendered from a fixed state, without view model nor database.
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
    fun home() = capture("home") {
        HomeScreen(userName = "Alice", onLogOut = {})
    }

    /**
     * Renders [content] and compares it with (or records) its reference image.
     *
     * @param animated true for content that animates forever: the clock is then driven by hand to
     * a fixed instant, so the captured frame is always the same.
     */
    private fun capture(name: String, animated: Boolean = false, content: @Composable () -> Unit) {
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
        composeRule.onRoot().captureRoboImage("src/test/screenshots/${name}_${variant.name}.png")
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
