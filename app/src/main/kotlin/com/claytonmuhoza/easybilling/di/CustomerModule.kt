package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.customer.ArchiveCustomer
import com.claytonmuhoza.easybilling.domain.customer.CreateCustomer
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.TinLookup
import com.claytonmuhoza.easybilling.domain.customer.UpdateCustomer
import com.claytonmuhoza.easybilling.domain.customer.VerifyTin
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the customers: their storage, the check of a TIN and their use cases. */
@Module
@InstallIn(SingletonComponent::class)
object CustomerModule {

    /** @return the customers kept in the local database. */
    @Provides
    fun customerRepository(storage: LocalStorage): CustomerRepository = storage.customers

    /** @return the use case checking a TIN with the OBR, on the active environment. */
    @Provides
    fun verifyTin(
        settings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        tokens: TokenProvider,
        lookup: TinLookup,
    ): VerifyTin = VerifyTin({ settings.activeLoginTarget(vault) }, tokens, lookup)

    /** @return the use case creating a customer. */
    @Provides
    fun createCustomer(
        access: AccessControl,
        customers: CustomerRepository,
        verifyTin: VerifyTin,
        auditLog: AuditLog,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): CreateCustomer = CreateCustomer(access, customers, verifyTin, auditLog, transactions, time)

    /** @return the use case changing a customer. */
    @Provides
    fun updateCustomer(
        access: AccessControl,
        customers: CustomerRepository,
        verifyTin: VerifyTin,
        auditLog: AuditLog,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): UpdateCustomer = UpdateCustomer(access, customers, verifyTin, auditLog, transactions, time)

    /** @return the use case archiving a customer. */
    @Provides
    fun archiveCustomer(
        access: AccessControl,
        customers: CustomerRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): ArchiveCustomer = ArchiveCustomer(access, customers, auditLog, transactions)
}
