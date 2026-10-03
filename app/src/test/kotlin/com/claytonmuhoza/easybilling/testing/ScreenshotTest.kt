package com.claytonmuhoza.easybilling.testing

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RuntimeEnvironment

/**
 * One rendering configuration of a screenshot.
 *
 * @property name suffix of the screenshot file.
 * @property qualifiers Robolectric resource qualifiers: language, screen size, theme, density.
 */
data class ScreenshotVariant(val name: String, val qualifiers: String) {
    override fun toString() = name

    /** The configurations every screen is captured in. */
    companion object {
        /** Device name, screen size qualifier, density qualifier. */
        private val devices = listOf(
            Triple("phone", "w360dp-h640dp", "xhdpi"),
            Triple("tablet", "w800dp-h1280dp", "mdpi"),
        )
        private val themes = mapOf("light" to "notnight", "dark" to "night")
        private val languages = listOf("en", "fr")

        /** @return every combination of screen size, theme and language. */
        fun all(): List<ScreenshotVariant> = devices.flatMap { (deviceName, size, density) ->
            themes.flatMap { (themeName, night) ->
                languages.map { language ->
                    ScreenshotVariant(
                        name = "${deviceName}_${themeName}_$language",
                        qualifiers = "$language-$size-$night-$density",
                    )
                }
            }
        }
    }
}

/**
 * Base of the screenshot tests of the screens: each test runs once per [ScreenshotVariant].
 *
 * A subclass is annotated with `@RunWith(ParameterizedRobolectricTestRunner::class)`,
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and `@Config(sdk = [35])`, and gives its parameters
 * with `ScreenshotVariant.all()`.
 *
 * References live in `src/test/screenshots`. Record them with `./gradlew recordRoborazziDebug`.
 * The emulated Android version is pinned: on Android 17 Robolectric renders asynchronously and the
 * capture sometimes comes out blank.
 *
 * @param variant the configuration of this run.
 */
abstract class ScreenshotTest(private val variant: ScreenshotVariant) {

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

    /**
     * Renders [content] on the surface the application puts behind every screen, and compares it
     * with (or records) its reference image.
     *
     * @param name name of the screenshot, before the variant.
     * @param animated true for content that animates forever: the clock is then driven by hand to
     * a fixed instant, so the captured frame is always the same.
     * @param content the screen to capture.
     */
    protected fun capture(name: String, animated: Boolean = false, content: @Composable () -> Unit) {
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

    private companion object {
        /** Instant of an endless animation at which its frame is captured. */
        const val ANIMATION_INSTANT_MILLIS = 500L
    }
}
