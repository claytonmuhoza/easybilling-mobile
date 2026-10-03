package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.obr.ObrHttp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** The HTTP client of the EBMS, alone in its module so the tests can trust their own server. */
@Module
@InstallIn(SingletonComponent::class)
object ObrHttpModule {

    /** @return the HTTP client: 15 second time out, no automatic retry, system certificates. */
    @Provides
    @Singleton
    fun obrHttpClient(): OkHttpClient = ObrHttp.client()
}
