package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.data.obr.ObrClient
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.EnvironmentLock
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.DispatchConnection
import com.claytonmuhoza.easybilling.domain.transmission.DispatchOutbox
import com.claytonmuhoza.easybilling.domain.transmission.DispatchStorage
import com.claytonmuhoza.easybilling.domain.transmission.ExponentialRetry
import com.claytonmuhoza.easybilling.domain.transmission.InvoiceEnvironmentLock
import com.claytonmuhoza.easybilling.domain.transmission.ObrGateway
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReadTransmissionStatus
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.domain.transmission.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Builds the transmission to the OBR: the gateway, the acknowledgements and the send queue. */
@Module
@InstallIn(SingletonComponent::class)
object TransmissionModule {

    /** @return the fiscal services of the EBMS (`addInvoice_confirm/`, `getInvoice/`). */
    @Provides
    fun obrGateway(client: ObrClient): ObrGateway = client

    /** @return the acknowledgements kept in the local database. */
    @Provides
    fun receiptArchive(storage: LocalStorage): ReceiptArchive = storage.receipts

    /** @return what keeps the fiscal documents with the environment they were issued for. */
    @Provides
    fun environmentLock(invoices: InvoiceRepository, outbox: OutboxRepository): EnvironmentLock =
        InvoiceEnvironmentLock(invoices, outbox)

    /** @return what the send queue reads and writes. */
    @Provides
    fun dispatchStorage(
        outbox: OutboxRepository,
        invoices: InvoiceRepository,
        receipts: ReceiptArchive,
        profiles: TaxProfileRepository,
        cancellations: InvoiceCancellationRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): DispatchStorage = DispatchStorage(outbox, invoices, receipts, profiles, cancellations, auditLog, transactions)

    /** @return how the send queue reaches the OBR, on the active environment. */
    @Provides
    fun dispatchConnection(
        settings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        tokens: TokenProvider,
        gateway: ObrGateway,
    ): DispatchConnection = DispatchConnection({ settings.activeLoginTarget(vault) }, tokens, gateway)

    /** @return the use case reading where the transmission stands. */
    @Provides
    fun readTransmissionStatus(
        outbox: OutboxRepository,
        invoices: InvoiceRepository,
        time: TimeProvider,
    ): ReadTransmissionStatus = ReadTransmissionStatus(outbox, invoices, time)

    /** @return the send queue; a single one, so only one pass runs at a time. */
    @Provides
    @Singleton
    fun dispatchOutbox(
        storage: DispatchStorage,
        connection: DispatchConnection,
        time: TimeProvider,
    ): DispatchOutbox = DispatchOutbox(storage, connection, ExponentialRetry, time)
}
