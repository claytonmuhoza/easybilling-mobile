package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.SaveTaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the configuration part of the domain: the tax profile, for now. */
@Module
@InstallIn(SingletonComponent::class)
object ConfigurationModule {

    /** @return the tax profile versions kept in the local database. */
    @Provides
    fun taxProfileRepository(storage: LocalStorage): TaxProfileRepository = storage.taxProfiles

    /** @return the use case saving the tax profile as a new version. */
    @Provides
    fun saveTaxProfile(
        profiles: TaxProfileRepository,
        auditLog: AuditLog,
        time: TimeProvider,
        transactions: TransactionRunner,
    ): SaveTaxProfile = SaveTaxProfile(profiles, auditLog, time, transactions)
}
