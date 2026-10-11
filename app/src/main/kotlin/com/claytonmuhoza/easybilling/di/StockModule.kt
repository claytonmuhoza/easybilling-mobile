package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.stock.InvoiceStock
import com.claytonmuhoza.easybilling.domain.stock.ReadStock
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.ResolveStockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import com.claytonmuhoza.easybilling.domain.stock.StockStorage
import com.claytonmuhoza.easybilling.domain.stock.UnitSalePrice
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the stock: its movements, the stock of the items and what a manager decides (UC-F01 to F10). */
@Module
@InstallIn(SingletonComponent::class)
object StockModule {

    /** @return the stock movements kept in the local database. */
    @Provides
    fun stockRepository(storage: LocalStorage): StockRepository = storage.stock

    /** @return who may do a sensitive action, alone or with the approval of a manager. */
    @Provides
    fun approvalAuthority(
        access: AccessControl,
        users: UserRepository,
        policy: PermissionPolicy,
    ): ApprovalAuthority = ApprovalAuthority(access, users, policy)

    /** @return the stock movements that come with the invoices and the credit notes. */
    @Provides
    fun invoiceStock(
        stock: StockRepository,
        numbering: NumberingRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
    ): InvoiceStock = InvoiceStock(stock, numbering, outbox, auditLog)

    /** @return where the record of a movement reads and writes. */
    @Provides
    fun stockStorage(
        items: ItemRepository,
        stock: StockRepository,
        numbering: NumberingRepository,
        ebmsSettings: EbmsSettingsRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
    ): StockStorage = StockStorage(items, stock, numbering, ebmsSettings, outbox, auditLog)

    /** @return the use case recording an entry or an exit of stock. */
    @Provides
    fun recordStockMovement(
        authority: ApprovalAuthority,
        storage: StockStorage,
        priceSale: PriceSale,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): RecordStockMovement =
        RecordStockMovement(authority, storage, UnitSalePrice(priceSale, time), transactions, time)

    /** @return the use case reading the stock of the items. */
    @Provides
    fun readStock(items: ItemRepository, stock: StockRepository, settings: StockSettingsRepository): ReadStock =
        ReadStock(items, stock, settings)

    /** @return the use case deciding what happens to a refused or uncertain movement. */
    @Provides
    fun resolveStockMovement(
        access: AccessControl,
        stock: StockRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): ResolveStockMovement = ResolveStockMovement(access, stock, outbox, auditLog, transactions, time)
}
