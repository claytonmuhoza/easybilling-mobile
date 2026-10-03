package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.ReadIdentifierContext
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the numbering of the documents and the check done before the cash register opens. */
@Module
@InstallIn(SingletonComponent::class)
object NumberingModule {

    /** @return the numbering and its counters kept in the local database. */
    @Provides
    fun numberingRepository(storage: LocalStorage): NumberingRepository = storage.numbering

    /** @return the use case saving the numbering. */
    @Provides
    fun saveNumbering(
        numbering: NumberingRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): SaveNumbering = SaveNumbering(numbering, auditLog, transactions)

    /** @return the use case giving the next number of a type of document. */
    @Provides
    fun allocateInvoiceNumber(
        numbering: NumberingRepository,
        time: TimeProvider,
        transactions: TransactionRunner,
    ): AllocateInvoiceNumber = AllocateInvoiceNumber(numbering, time, transactions)

    /** @return the use case reading what the identifiers of the invoices are built from. */
    @Provides
    fun readIdentifierContext(
        profiles: TaxProfileRepository,
        ebmsSettings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
    ): ReadIdentifierContext = ReadIdentifierContext(profiles, ebmsSettings, vault)

    /** @return the use case telling whether invoices can be issued. */
    @Provides
    fun checkBillingReadiness(
        profiles: TaxProfileRepository,
        ebmsSettings: EbmsSettingsRepository,
        taxes: TaxRepository,
        numbering: NumberingRepository,
        time: TimeProvider,
    ): CheckBillingReadiness = CheckBillingReadiness(profiles, ebmsSettings, taxes, numbering, time)
}
