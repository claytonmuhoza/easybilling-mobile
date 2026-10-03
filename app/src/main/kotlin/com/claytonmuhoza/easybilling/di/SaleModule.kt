package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.IssueConfiguration
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueStorage
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the sale: the drafts, the issued invoices, the send queue and their use cases. */
@Module
@InstallIn(SingletonComponent::class)
object SaleModule {

    /** @return the sales that are not issued, kept in the local database. */
    @Provides
    fun saleDraftRepository(storage: LocalStorage): SaleDraftRepository = storage.saleDrafts

    /** @return the issued invoices kept in the local database. */
    @Provides
    fun invoiceRepository(storage: LocalStorage): InvoiceRepository = storage.invoices

    /** @return the send queue kept in the local database. */
    @Provides
    fun outboxRepository(storage: LocalStorage): OutboxRepository = storage.outbox

    /** @return the use case computing a sale with the prices and the rates of today. */
    @Provides
    fun priceSale(
        items: ItemRepository,
        taxes: TaxRepository,
        customers: CustomerRepository,
        profiles: TaxProfileRepository,
        time: TimeProvider,
    ): PriceSale = PriceSale(items, taxes, customers, profiles, time)

    /** @return the use case changing the sale being typed and the sales on hold. */
    @Provides
    fun editSale(drafts: SaleDraftRepository, time: TimeProvider, transactions: TransactionRunner): EditSale =
        EditSale(drafts, time, transactions)

    /** @return what an invoice copies from the configuration. */
    @Provides
    fun issueConfiguration(
        profiles: TaxProfileRepository,
        ebmsSettings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        readiness: CheckBillingReadiness,
    ): IssueConfiguration = IssueConfiguration(profiles, ebmsSettings, vault, readiness)

    /** @return where the issue of a sale writes. */
    @Provides
    fun issueStorage(
        drafts: SaleDraftRepository,
        invoices: InvoiceRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
    ): IssueStorage = IssueStorage(drafts, invoices, outbox, auditLog)

    /** @return the use case issuing a sale as an invoice. */
    @Provides
    fun issueSale(
        configuration: IssueConfiguration,
        storage: IssueStorage,
        priceSale: PriceSale,
        allocateNumber: AllocateInvoiceNumber,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): IssueSale = IssueSale(configuration, storage, priceSale, allocateNumber, transactions, time)
}
