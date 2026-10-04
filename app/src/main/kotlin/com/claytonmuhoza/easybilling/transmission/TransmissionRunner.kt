package com.claytonmuhoza.easybilling.transmission

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.DispatchOutbox
import com.claytonmuhoza.easybilling.domain.transmission.NetworkMonitor
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionScheduler
import com.claytonmuhoza.easybilling.ui.storageCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a pass over the send queue needs besides the queue itself.
 *
 * @property outbox the send queue, read to plan the next pass.
 * @property monitor tells when the network comes back.
 * @property scheduler plans the next pass for when the application is closed.
 * @property time source of the current time.
 */
class TransmissionEnvironment @Inject constructor(
    val outbox: OutboxRepository,
    val monitor: NetworkMonitor,
    val scheduler: TransmissionScheduler,
    val time: TimeProvider,
)

/**
 * Sends the queue to the OBR without making any screen wait (UC-D02). It lives as long as the
 * application: a pass goes on when the screen that asked for it is left. After each pass it plans
 * the next one, so the queue also empties when the application is closed.
 *
 * @param dispatch a pass over the send queue.
 * @param environment the network, the planning of the next pass and the clock.
 */
@Singleton
class TransmissionRunner @Inject constructor(
    private val dispatch: DispatchOutbox,
    private val environment: TransmissionEnvironment,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutablePasses = MutableStateFlow(0)
    private val mutableOfflineSince = MutableStateFlow(
        if (environment.monitor.online.value) null else environment.time.now(),
    )

    /** How many passes ended: a screen that shows a status reads it again when this changes. */
    val passes: StateFlow<Int> = mutablePasses.asStateFlow()

    /** Since when the device has no network, or null when it has one. */
    val offlineSince: StateFlow<Instant?> = mutableOfflineSince.asStateFlow()

    init {
        scope.launch {
            environment.monitor.online.drop(1).collect { online ->
                mutableOfflineSince.value = if (online) null else environment.time.now()
                // The network is back: what waited leaves at once, whatever its wait.
                if (online) pass(force = true)
            }
        }
    }

    /**
     * Starts a pass over the queue and returns at once.
     *
     * @param force true to try the operations whose wait is not over (UC-D05).
     */
    fun kick(force: Boolean = false) {
        scope.launch { pass(force) }
    }

    /**
     * Runs a pass over the queue, then plans the next one if operations are left.
     *
     * @param force true to try the operations whose wait is not over.
     * @param inBackground true when the pass is the planned one, run while the application may be closed.
     */
    suspend fun pass(force: Boolean = false, inBackground: Boolean = false) {
        // A failure of the local storage leaves the queue as it is: the next pass tries again.
        storageCall {
            dispatch(force)
            val next = environment.outbox.pending().firstOrNull()
            if (next == null) {
                environment.scheduler.cancel()
            } else {
                val wait = next.nextAttemptAt?.let { Duration.between(environment.time.now(), it) } ?: Duration.ZERO
                environment.scheduler.schedule(maxOf(wait, Duration.ZERO), afterRunningPass = inBackground)
            }
        }
        mutablePasses.update { it + 1 }
    }
}
