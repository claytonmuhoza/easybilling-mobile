package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.RedoSale
import com.claytonmuhoza.easybilling.domain.invoicing.ResendRejectedInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.DispatchConnection
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.VerifyInvoice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds what follows an issued invoice: its check at the OBR and the ways out of a refusal. */
@Module
@InstallIn(SingletonComponent::class)
object InvoiceModule {

    /** @return the issued invoices as the list shows them, read from the local database. */
    @Provides
    fun invoiceListing(storage: LocalStorage): InvoiceListing = storage.invoiceListing

    /** @return the use case asking the OBR for an invoice, on the active environment. */
    @Provides
    fun verifyInvoice(invoices: InvoiceRepository, connection: DispatchConnection): VerifyInvoice =
        VerifyInvoice(invoices, connection)

    /** @return the use case putting a refused invoice back in the send queue. */
    @Provides
    fun resendRejectedInvoice(
        access: AccessControl,
        invoices: InvoiceRepository,
        profiles: TaxProfileRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): ResendRejectedInvoice = ResendRejectedInvoice(access, invoices, profiles, outbox, auditLog, transactions, time)

    /** @return the use case starting the sale of an invoice over. */
    @Provides
    fun redoSale(
        invoices: InvoiceRepository,
        items: ItemRepository,
        customers: CustomerRepository,
        drafts: SaleDraftRepository,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): RedoSale = RedoSale(invoices, items, customers, drafts, transactions, time)
}
