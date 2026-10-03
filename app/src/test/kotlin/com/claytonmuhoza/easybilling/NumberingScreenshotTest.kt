package com.claytonmuhoza.easybilling

import android.app.Application
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingProblem
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.numbering.NumberingActions
import com.claytonmuhoza.easybilling.numbering.NumberingFeedback
import com.claytonmuhoza.easybilling.numbering.NumberingFields
import com.claytonmuhoza.easybilling.numbering.NumberingPreview
import com.claytonmuhoza.easybilling.numbering.NumberingScreen
import com.claytonmuhoza.easybilling.numbering.NumberingUiState
import com.claytonmuhoza.easybilling.numbering.YearFormat
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.Test

/** Screenshots of the numbering screen, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class NumberingScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private fun preview(prefix: String = "", counter: String = "00001", identifier: String? = null) = NumberingPreview(
        numbers = DocumentType.entries.associateWith { "$prefix${it.code}-$counter/2026" },
        identifier = identifier,
    )

    @Test
    fun numberingDefault() = capture("numbering") {
        NumberingScreen(
            state = NumberingUiState.Editing(NumberingFields(), preview()),
            actions = NumberingActions(),
        )
    }

    @Test
    fun numberingSeveralDevicesSaved() = capture("numbering_devices_saved") {
        NumberingScreen(
            state = NumberingUiState.Editing(
                fields = NumberingFields(multipleDevices = true, deviceCode = "A1", prefix = "BJ."),
                preview = preview(
                    prefix = "BJ.A1-",
                    identifier = "4000000001/ws400000000100027/20261003100000/BJ.A1-FN-00001/2026",
                ),
                feedback = NumberingFeedback.SAVED,
            ),
            actions = NumberingActions(),
        )
    }

    @Test
    fun numberingWithErrors() = capture("numbering_errors") {
        NumberingScreen(
            state = NumberingUiState.Editing(
                fields = NumberingFields(multipleDevices = true, prefix = "FAC 2026", year = YearFormat.NONE),
                preview = null,
                problems = setOf(
                    NumberingProblem.DEVICE_CODE_INVALID,
                    NumberingProblem.INVALID_CHARACTER,
                    NumberingProblem.YEAR_MISSING,
                    NumberingProblem.IDENTIFIER_TOO_LONG,
                ),
                feedback = NumberingFeedback.FAILED,
            ),
            actions = NumberingActions(),
        )
    }

    @Test
    fun numberingLocked() = capture("numbering_locked") {
        val lockedAt = Instant.parse("2026-10-03T12:30:00Z")
        NumberingScreen(
            state = NumberingUiState.Locked(
                settings = NumberingSettings(
                    template = "{DEVICE}-{TYPE}-{NNNNN}/{YYYY}",
                    reset = CounterReset.YEARLY,
                    multipleDevices = true,
                    deviceCode = "A1",
                    lockedAt = lockedAt,
                ),
                lockedAt = lockedAt,
                preview = preview(
                    prefix = "A1-",
                    counter = "00042",
                    identifier = "4000000001/ws400000000100027/20261003143000/A1-FN-00042/2026",
                ),
            ),
            actions = NumberingActions(),
        )
    }

    @Test
    fun numberingLoading() = capture("numbering_loading", animated = true) {
        NumberingScreen(state = NumberingUiState.Loading, actions = NumberingActions())
    }

    @Test
    fun numberingError() = capture("numbering_error") {
        NumberingScreen(state = NumberingUiState.Failed, actions = NumberingActions())
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
