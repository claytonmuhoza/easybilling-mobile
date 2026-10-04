package com.claytonmuhoza.easybilling

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.claytonmuhoza.easybilling.transmission.WorkManagerTransmissionScheduler
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The planning of the next pass over the send queue by WorkManager (UC-D02). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WorkManagerTransmissionSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = WorkManagerTransmissionScheduler(context)

    @Before
    fun startWorkManager() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    /** The planned passes that did not run and were not cancelled. */
    private fun planned(): List<WorkInfo> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WorkManagerTransmissionScheduler.WORK_NAME)
        .get()
        .filter { !it.state.isFinished }

    @Test
    fun `a planned pass waits for its delay and for a network`() {
        scheduler.schedule(Duration.ofSeconds(30))

        val pass = planned().single()
        assertEquals(WorkInfo.State.ENQUEUED, pass.state)
        assertEquals(NetworkType.CONNECTED, pass.constraints.requiredNetworkType)
        assertEquals(Duration.ofSeconds(30).toMillis(), pass.initialDelayMillis)
    }

    @Test
    fun `a new plan replaces the previous one`() {
        scheduler.schedule(Duration.ofSeconds(30))

        scheduler.schedule(Duration.ofMinutes(5))

        assertEquals(Duration.ofMinutes(5).toMillis(), planned().single().initialDelayMillis)
    }

    @Test
    fun `the pass planned by the running pass is chained after it instead of cancelling it`() {
        scheduler.schedule(Duration.ofSeconds(30))

        scheduler.schedule(Duration.ofMinutes(1), afterRunningPass = true)

        // The first pass still waits; the second one is blocked until it ends.
        assertEquals(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), planned().map { it.state }.sorted())
    }

    @Test
    fun `nothing stays planned once the queue is empty`() {
        scheduler.schedule(Duration.ofSeconds(30))

        scheduler.cancel()

        assertEquals(emptyList(), planned())
    }
}
