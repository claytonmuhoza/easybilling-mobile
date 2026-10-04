package com.claytonmuhoza.easybilling.di

import android.content.Context
import com.claytonmuhoza.easybilling.domain.transmission.NetworkMonitor
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionScheduler
import com.claytonmuhoza.easybilling.platform.network.AndroidNetworkMonitor
import com.claytonmuhoza.easybilling.transmission.WorkManagerTransmissionScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * What the device does behind the screens: watching the network and planning background work.
 * Alone in its module so the tests can replace both.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackgroundModule {

    /** @return the state of the network of the device. */
    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context): NetworkMonitor = AndroidNetworkMonitor(context)

    /** @return the planning of the next pass over the send queue, by WorkManager. */
    @Provides
    @Singleton
    fun transmissionScheduler(@ApplicationContext context: Context): TransmissionScheduler =
        WorkManagerTransmissionScheduler(context)
}
