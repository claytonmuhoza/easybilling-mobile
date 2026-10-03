package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.data.local.configuration.SecretStoreCredentialsVault
import com.claytonmuhoza.easybilling.data.obr.ObrClient
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.SaveEbmsConnection
import com.claytonmuhoza.easybilling.domain.configuration.SwitchEbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.TestEbmsConnection
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.identity.SecretGuard
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.CachingTokenProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeJournal
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import com.claytonmuhoza.easybilling.domain.transmission.SetExchangeRetention
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Builds the connection to the EBMS: settings, credentials, client, token and log of the exchanges. */
@Module
@InstallIn(SingletonComponent::class)
object EbmsModule {

    /** @return the EBMS settings kept in the local database. */
    @Provides
    fun ebmsSettings(storage: LocalStorage): EbmsSettingsRepository = storage.ebmsSettings

    /** @return the exchanges kept in the local database. */
    @Provides
    fun exchangeLog(storage: LocalStorage): ExchangeLog = storage.exchangeLog

    /** @return the EBMS credentials, encrypted by the Android Keystore. */
    @Provides
    @Singleton
    fun credentialsVault(secretStore: SecretStore): EbmsCredentialsVault = SecretStoreCredentialsVault(secretStore)

    /** @return the log of the exchanges, purged after the chosen number of days. */
    @Provides
    @Singleton
    fun exchangeJournal(log: ExchangeLog, settings: EbmsSettingsRepository, time: TimeProvider): ExchangeJournal =
        ExchangeJournal(log, settings, time)

    /** @return the login to the EBMS, through the client that records every exchange. */
    @Provides
    @Singleton
    fun obrAuthentication(http: OkHttpClient, journal: ExchangeJournal, time: TimeProvider): ObrAuthentication =
        ObrClient(http, journal, time)

    /** @return the token of the active environment, shared by every call (used from step 10). */
    @Provides
    @Singleton
    fun tokenProvider(
        settings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        authentication: ObrAuthentication,
        time: TimeProvider,
    ): TokenProvider = CachingTokenProvider({ settings.activeLoginTarget(vault) }, authentication, time)

    /** @return the use case saving the address and the credentials of an environment. */
    @Provides
    fun saveEbmsConnection(
        settings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        auditLog: AuditLog,
    ): SaveEbmsConnection = SaveEbmsConnection(settings, vault, auditLog)

    /** @return the use case choosing the active environment. */
    @Provides
    fun switchEbmsEnvironment(
        settings: EbmsSettingsRepository,
        users: UserRepository,
        guard: SecretGuard,
        auditLog: AuditLog,
    ): SwitchEbmsEnvironment = SwitchEbmsEnvironment(settings, users, guard, auditLog)

    /** @return the use case testing the connection to the active environment. */
    @Provides
    fun testEbmsConnection(
        settings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        authentication: ObrAuthentication,
        time: TimeProvider,
        auditLog: AuditLog,
    ): TestEbmsConnection = TestEbmsConnection(settings, vault, authentication, time, auditLog)

    /** @return the use case choosing how long the exchanges are kept. */
    @Provides
    fun setExchangeRetention(
        settings: EbmsSettingsRepository,
        journal: ExchangeJournal,
        auditLog: AuditLog,
    ): SetExchangeRetention = SetExchangeRetention(settings, journal, auditLog)
}
