package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.obr.ObrClient
import com.claytonmuhoza.easybilling.domain.customer.TinLookup
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeJournal
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Builds the client of the EBMS and gives it to the domain, one port per service. */
@Module
@InstallIn(SingletonComponent::class)
object ObrClientModule {

    /** @return the client of the EBMS, which records every exchange. */
    @Provides
    @Singleton
    fun obrClient(http: OkHttpClient, journal: ExchangeJournal, time: TimeProvider): ObrClient =
        ObrClient(http, journal, time)

    /** @return the login to the EBMS (`login/`). */
    @Provides
    fun obrAuthentication(client: ObrClient): ObrAuthentication = client

    /** @return the TIN register of the OBR (`checkTIN/`). */
    @Provides
    fun tinLookup(client: ObrClient): TinLookup = client
}
