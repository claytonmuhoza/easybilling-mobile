package com.claytonmuhoza.easybilling.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.claytonmuhoza.easybilling.domain.transmission.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [NetworkMonitor] that follows the default network of the device. It tells whether a network is
 * there, not whether the OBR answers: only a call can tell that.
 *
 * @param context any context of the application.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(connected())

    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = true
                }

                override fun onLost(network: Network) {
                    state.value = false
                }
            },
        )
    }

    /** @return whether the default network of the device can reach the Internet now. */
    private fun connected(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
