package com.claytonmuhoza.easybilling.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The network monitor follows the default network of the device. */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun internet(available: Boolean) {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        if (available) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    @Test
    fun `it starts with the state of the default network`() {
        internet(available = true)
        assertTrue(AndroidNetworkMonitor(context).online.value)

        internet(available = false)
        assertFalse(AndroidNetworkMonitor(context).online.value)
    }

    @Test
    fun `it follows the network when it is lost and when it comes back`() {
        internet(available = true)
        val monitor = AndroidNetworkMonitor(context)
        val callback = shadowOf(manager).networkCallbacks.single()

        callback.onLost(manager.activeNetwork!!)
        assertEquals(false, monitor.online.value)

        callback.onAvailable(manager.activeNetwork!!)
        assertEquals(true, monitor.online.value)
    }
}
