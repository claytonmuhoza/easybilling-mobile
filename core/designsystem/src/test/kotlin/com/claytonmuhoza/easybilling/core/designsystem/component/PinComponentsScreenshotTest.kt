package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.claytonmuhoza.easybilling.core.designsystem.component.StateComponentsScreenshotTest.Variant
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
 * Screenshots of the entry screen with the brand mark, the avatar, the PIN dots and the PIN pad,
 * in every supported language, theme and screen size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PinComponentsScreenshotTest(private val variant: Variant) {

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
    fun pinEntry() = capture("pin_entry") {
        Sample(pin = "1234", message = if (variant.french) "6 à 12 chiffres" else "6 to 12 digits")
    }

    @Test
    fun pinEntryError() = capture("pin_entry_error") {
        Sample(
            pin = "",
            message = if (variant.french) {
                "Code PIN incorrect. Il reste 9 tentatives."
            } else {
                "Wrong PIN. 9 attempts left."
            },
            isError = true,
        )
    }

    @Test
    fun pinEntryDisabled() = capture("pin_entry_disabled") {
        Sample(
            pin = "",
            message = if (variant.french) {
                "Compte bloqué. Réessayez dans 60 secondes."
            } else {
                "Account locked. Try again in 60 seconds."
            },
            isError = true,
            enabled = false,
            onBack = {},
        )
    }

    @Composable
    private fun Sample(
        pin: String,
        message: String,
        isError: Boolean = false,
        enabled: Boolean = true,
        onBack: (() -> Unit)? = null,
    ) {
        EntryScreen(
            onBack = onBack,
            header = {
                BrandMark()
                Text("Boutique Umuco", style = MaterialTheme.typography.titleLarge)
                InitialsAvatar("Aline N.")
            },
        ) {
            PinEntry(
                pin = pin,
                onPinChange = {},
                maxLength = 12,
                onSubmit = {},
                submitDescription = if (variant.french) "Se connecter" else "Log in",
                slots = 6,
                message = message,
                isError = isError,
                enabled = enabled,
            )
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            EasyBillingTheme {
                Surface { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/${name}_${variant.name}.png")
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Variant> = StateComponentsScreenshotTest.variants()
    }
}
