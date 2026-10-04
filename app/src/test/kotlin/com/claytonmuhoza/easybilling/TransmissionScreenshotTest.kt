package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.transmission.QueuedDocument
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionStatus
import com.claytonmuhoza.easybilling.testing.ScreenshotTest
import com.claytonmuhoza.easybilling.testing.ScreenshotVariant
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import com.claytonmuhoza.easybilling.transmission.TransmissionActions
import com.claytonmuhoza.easybilling.transmission.TransmissionBanner
import com.claytonmuhoza.easybilling.transmission.TransmissionScreen
import com.claytonmuhoza.easybilling.transmission.TransmissionUiState
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.test.Test

/** Screenshots of the transmission banner and of the transmission screen, in every [ScreenshotVariant]. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TransmissionScreenshotTest(variant: ScreenshotVariant) : ScreenshotTest(variant) {

    private val now = TransmissionSamples.NOW

    private fun ready(
        queue: List<QueuedDocument> = emptyList(),
        rejected: List<RejectedDocument> = emptyList(),
        sentToday: Int = 12,
        offlineFor: Duration? = null,
    ) = TransmissionUiState.Ready(
        status = TransmissionStatus(queue = queue, rejected = rejected, sentToday = sentToday, readAt = now),
        online = offlineFor == null,
        offlineSince = offlineFor?.let { now - it },
    )

    private fun waiting(wait: Duration) = listOf(TransmissionSamples.queued(41, wait, attempts = 3))

    private val longQueue = listOf(
        TransmissionSamples.queued(41, Duration.ofMinutes(47), attempts = 4, uncertain = true),
        TransmissionSamples.queued(42, Duration.ofMinutes(31), customer = "Brasserie du Lac SA"),
        TransmissionSamples.queued(43, Duration.ofMinutes(2)),
    )

    @Composable
    private fun banner(state: TransmissionUiState.Ready, compact: Boolean = false) =
        TransmissionBanner(state, compact, onOpen = {}, modifier = Modifier.padding(Spacing.large))

    @Composable
    private fun screen(state: TransmissionUiState) = TransmissionScreen(state, TransmissionActions())

    @Test
    fun bannerSynchronised() = capture("transmission_banner_synchronised") {
        banner(ready())
    }

    @Test
    fun bannerWaiting() = capture("transmission_banner_waiting") {
        banner(ready(queue = waiting(Duration.ofMinutes(14))))
    }

    @Test
    fun bannerLate() = capture("transmission_banner_late") {
        banner(ready(queue = waiting(Duration.ofHours(5))))
    }

    @Test
    fun bannerAlert() = capture("transmission_banner_alert") {
        banner(ready(queue = waiting(Duration.ofHours(30))))
    }

    @Test
    fun bannerRejected() = capture("transmission_banner_rejected") {
        banner(ready(rejected = listOf(TransmissionSamples.rejected(38))))
    }

    @Test
    fun bannerOffline() = capture("transmission_banner_offline") {
        banner(ready(queue = longQueue, offlineFor = Duration.ofMinutes(47)))
    }

    @Test
    fun bannerCompact() = capture("transmission_banner_compact") {
        banner(ready(queue = longQueue, offlineFor = Duration.ofMinutes(47)), compact = true)
    }

    @Test
    fun transmissionSynchronised() = capture("transmission_synchronised") {
        screen(ready())
    }

    @Test
    fun transmissionOffline() = capture("transmission_offline") {
        screen(ready(queue = longQueue, offlineFor = Duration.ofMinutes(47)))
    }

    @Test
    fun transmissionRejected() = capture("transmission_rejected") {
        val rejected = listOf(
            TransmissionSamples.rejected(38),
            TransmissionSamples.rejected(35, "Le NIF du contribuable est invalide."),
        )
        screen(ready(queue = waiting(Duration.ofMinutes(14)), rejected = rejected))
    }

    @Test
    fun transmissionLoading() = capture("transmission_loading", animated = true) {
        screen(TransmissionUiState.Loading)
    }

    @Test
    fun transmissionError() = capture("transmission_error") {
        screen(TransmissionUiState.Failed)
    }

    /** The configurations of the screenshots. */
    companion object {
        /** @return every combination of screen size, theme and language. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<ScreenshotVariant> = ScreenshotVariant.all()
    }
}
