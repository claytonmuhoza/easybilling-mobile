package com.claytonmuhoza.easybilling.transmission

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration

/**
 * The planned pass over the send queue. WorkManager runs it once a network is available, even if
 * the application was closed or the device restarted in the meantime (UC-D02).
 *
 * @param context the context of the application.
 * @param parameters what WorkManager gives to the work.
 */
class TransmissionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    /** Gives the planned pass what the application injects elsewhere. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        /** @return what sends the queue. */
        fun runner(): TransmissionRunner
    }

    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        // The pass plans the next one itself: this work always ends well.
        dependencies.runner().pass(inBackground = true)
        return Result.success()
    }
}

/**
 * [TransmissionScheduler] backed by WorkManager: one planned pass at most, which waits for a network.
 *
 * @param context any context of the application.
 */
class WorkManagerTransmissionScheduler(private val context: Context) : TransmissionScheduler {

    override fun schedule(delay: Duration, afterRunningPass: Boolean) {
        val request = OneTimeWorkRequestBuilder<TransmissionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay)
            .build()
        // Replacing would cancel the pass that asks for its successor: it is chained after it instead.
        val policy = if (afterRunningPass) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Names of the planned work. */
    companion object {
        /** Unique name of the planned pass. */
        const val WORK_NAME = "obr-transmission"
    }
}
