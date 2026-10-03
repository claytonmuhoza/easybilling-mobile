package com.claytonmuhoza.easybilling.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import com.claytonmuhoza.easybilling.core.designsystem.component.StateComponentsScreenshotTest.Variant
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
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
 * Screenshots of the icons and of the display components (status, amount, cards, stepper, search)
 * in every supported language, theme and screen size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DisplayComponentsScreenshotTest(private val variant: Variant) {

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

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun icons() = capture("icons") {
        FlowRow(
            modifier = Modifier.padding(Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            AppIcon.entries.forEach { IconTile(it) }
        }
    }

    @Test
    fun displayComponents() = capture("display_components") {
        val french = variant.french
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            StatusChip(StatusTone.SUCCESS, if (french) "Enregistrée" else "Registered")
            StatusChip(StatusTone.PENDING, if (french) "En attente" else "Pending")
            StatusChip(StatusTone.ERROR, if (french) "Rejetée" else "Rejected")
            StatusChip(StatusTone.NEUTRAL, if (french) "Annulée" else "Cancelled")
            StatusBanner(
                tone = StatusTone.SUCCESS,
                text = if (french) "NIF reconnu par l'OBR : LE LAC" else "TIN recognised by the OBR: LE LAC",
            )
            AmountText(
                amount = if (french) "146 566" else "146,566",
                currency = "BIF",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            QuantityStepper("12", {}, {}, "less", "more")
            SearchField("", {}, label = if (french) "Rechercher un article" else "Search an item")
            NavigationRow(
                title = "Taxes",
                onClick = {},
                supporting = if (french) "2 taxes actives" else "2 active taxes",
                icon = AppIcon.TAG,
                trailing = { StatusChip(StatusTone.SUCCESS, if (french) "Fait" else "Done") },
            )
            TonalCard {
                Text(
                    text = if (french) "Carte en surface tonale" else "Card on a tonal surface",
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
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
