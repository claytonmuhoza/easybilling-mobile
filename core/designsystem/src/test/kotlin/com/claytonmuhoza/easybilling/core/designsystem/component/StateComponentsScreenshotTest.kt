package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
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
 * Screenshots of the state components in every supported language, theme and screen size.
 *
 * References live in `src/test/screenshots`. Record them with `./gradlew recordRoborazziDebug`.
 *
 * The emulated Android version is pinned: on Android 17 Robolectric renders asynchronously and the
 * capture sometimes comes out blank. Pinning also keeps the references stable across Robolectric
 * upgrades.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StateComponentsScreenshotTest(private val variant: Variant) {

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
    fun loadingState() = capture("loading_state", animated = true) {
        LoadingState()
    }

    @Test
    fun emptyState() = capture("empty_state") {
        if (variant.french) {
            EmptyState(
                title = "Aucune facture",
                message = "Les factures émises apparaîtront ici.",
                action = StateAction("Nouvelle vente") {},
            )
        } else {
            EmptyState(
                title = "No invoice",
                message = "Issued invoices will appear here.",
                action = StateAction("New sale") {},
            )
        }
    }

    @Test
    fun errorState() = capture("error_state") {
        ErrorState(
            message = if (variant.french) {
                "Le serveur de l'OBR n'a pas répondu. Vérifiez votre connexion."
            } else {
                "The OBR server did not answer. Check your connection."
            },
            onRetry = {},
        )
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
     * @property french whether the sample texts supplied by the test are in French.
     */
    data class Variant(val name: String, val qualifiers: String, val french: Boolean) {
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
                        french = language == "fr",
                    )
                }
            }
        }
    }
}
