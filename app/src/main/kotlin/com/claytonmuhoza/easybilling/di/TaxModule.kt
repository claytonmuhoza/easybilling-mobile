package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.ArchiveTax
import com.claytonmuhoza.easybilling.domain.configuration.CreateTax
import com.claytonmuhoza.easybilling.domain.configuration.ScheduleTaxRate
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.invoicing.LineTaxCalculator
import com.claytonmuhoza.easybilling.domain.invoicing.ObrLineTaxCalculator
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the taxes and the calculation of a line. */
@Module
@InstallIn(SingletonComponent::class)
object TaxModule {

    /** @return the tax definitions kept in the local database. */
    @Provides
    fun taxRepository(storage: LocalStorage): TaxRepository = storage.taxes

    /** @return the formula of the OBR, with the rounding of decision D08. */
    @Provides
    fun lineTaxCalculator(): LineTaxCalculator = ObrLineTaxCalculator()

    /** @return the use case creating a tax. */
    @Provides
    fun createTax(
        taxes: TaxRepository,
        auditLog: AuditLog,
        time: TimeProvider,
        transactions: TransactionRunner,
    ): CreateTax = CreateTax(taxes, auditLog, time, transactions)

    /** @return the use case scheduling a new rate. */
    @Provides
    fun scheduleTaxRate(
        taxes: TaxRepository,
        auditLog: AuditLog,
        time: TimeProvider,
        transactions: TransactionRunner,
    ): ScheduleTaxRate = ScheduleTaxRate(taxes, auditLog, time, transactions)

    /** @return the use case archiving a tax. */
    @Provides
    fun archiveTax(taxes: TaxRepository, auditLog: AuditLog, transactions: TransactionRunner): ArchiveTax =
        ArchiveTax(taxes, auditLog, transactions)
}
