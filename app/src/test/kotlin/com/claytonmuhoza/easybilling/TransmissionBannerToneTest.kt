package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.transmission.QueuedDocument
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionStatus
import com.claytonmuhoza.easybilling.testing.TransmissionSamples
import com.claytonmuhoza.easybilling.transmission.TransmissionUiState
import com.claytonmuhoza.easybilling.transmission.tone
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colour of the transmission banner (UC-D03): green, orange after an hour, red after a day or a refusal. */
class TransmissionBannerToneTest {

    private fun tone(
        queue: List<QueuedDocument> = emptyList(),
        rejected: List<RejectedDocument> = emptyList(),
        online: Boolean = true,
    ): StatusTone {
        val status = TransmissionStatus(queue = queue, rejected = rejected, readAt = TransmissionSamples.NOW)
        return TransmissionUiState.Ready(status, online).tone()
    }

    private fun waiting(wait: Duration) = listOf(TransmissionSamples.queued(1, wait))

    @Test
    fun `the banner is green when the device is online and nothing waits`() {
        assertEquals(StatusTone.SUCCESS, tone())
    }

    @Test
    fun `the banner is orange as soon as a document waits or the network is down`() {
        assertEquals(StatusTone.PENDING, tone(queue = waiting(Duration.ofMinutes(59))))
        assertEquals(StatusTone.PENDING, tone(online = false))
    }

    @Test
    fun `the banner stays orange from one hour of waiting to one day`() {
        assertEquals(StatusTone.PENDING, tone(queue = waiting(Duration.ofHours(1))))
        assertEquals(StatusTone.PENDING, tone(queue = waiting(Duration.ofHours(24) - Duration.ofSeconds(1))))
    }

    @Test
    fun `the banner is red after a day of waiting`() {
        assertEquals(StatusTone.ERROR, tone(queue = waiting(Duration.ofHours(24))))
        assertEquals(StatusTone.ERROR, tone(queue = waiting(Duration.ofHours(24)), online = false))
    }

    @Test
    fun `the banner is red as soon as an invoice is refused, even if nothing waits`() {
        assertEquals(StatusTone.ERROR, tone(rejected = listOf(TransmissionSamples.rejected(7))))
    }
}
