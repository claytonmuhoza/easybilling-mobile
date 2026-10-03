package com.claytonmuhoza.easybilling.transmission

import com.claytonmuhoza.easybilling.domain.transmission.DispatchOutbox
import com.claytonmuhoza.easybilling.ui.storageCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the queue to the OBR without making any screen wait. It lives as long as the application:
 * a pass goes on when the screen that asked for it is left.
 *
 * @param dispatch a pass over the send queue.
 */
@Singleton
class TransmissionRunner @Inject constructor(private val dispatch: DispatchOutbox) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutablePasses = MutableStateFlow(0)

    /** How many passes ended: a screen that shows a status reads it again when this changes. */
    val passes: StateFlow<Int> = mutablePasses.asStateFlow()

    /** Starts a pass over the queue; operations whose wait is not over are left for later. */
    fun kick() {
        scope.launch {
            // A failure of the local storage leaves the queue as it is: the next pass tries again.
            storageCall { dispatch() }
            mutablePasses.update { it + 1 }
        }
    }
}
