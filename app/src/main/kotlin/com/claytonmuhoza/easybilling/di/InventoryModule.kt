package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.stock.CloseInventory
import com.claytonmuhoza.easybilling.domain.stock.CountInventory
import com.claytonmuhoza.easybilling.domain.stock.InventoryRepository
import com.claytonmuhoza.easybilling.domain.stock.ReadInventory
import com.claytonmuhoza.easybilling.domain.stock.SetReorderThreshold
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import com.claytonmuhoza.easybilling.domain.stock.StockStorage
import com.claytonmuhoza.easybilling.domain.stock.UnitSalePrice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the inventory and the reorder thresholds of the stock (UC-F08, F10). */
@Module
@InstallIn(SingletonComponent::class)
object InventoryModule {

    /** @return the inventory in progress kept in the local database. */
    @Provides
    fun inventoryRepository(storage: LocalStorage): InventoryRepository = storage.inventory

    /** @return the reorder thresholds kept in the local database. */
    @Provides
    fun stockSettingsRepository(storage: LocalStorage): StockSettingsRepository = storage.stockSettings

    /** @return the use case reading the inventory in progress. */
    @Provides
    fun readInventory(
        items: ItemRepository,
        stock: StockRepository,
        inventory: InventoryRepository,
    ): ReadInventory = ReadInventory(items, stock, inventory)

    /** @return the use case keeping what is counted during an inventory. */
    @Provides
    fun countInventory(
        access: AccessControl,
        items: ItemRepository,
        inventory: InventoryRepository,
    ): CountInventory = CountInventory(access, items, inventory)

    /** @return the use case closing the inventory and adjusting the stock. */
    @Provides
    fun closeInventory(
        authority: ApprovalAuthority,
        storage: StockStorage,
        inventory: InventoryRepository,
        priceSale: PriceSale,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): CloseInventory =
        CloseInventory(authority, storage, inventory, UnitSalePrice(priceSale, time), transactions, time)

    /** @return the use case setting the reorder threshold of an item. */
    @Provides
    fun setReorderThreshold(
        access: AccessControl,
        items: ItemRepository,
        settings: StockSettingsRepository,
        auditLog: AuditLog,
    ): SetReorderThreshold = SetReorderThreshold(access, items, settings, auditLog)
}
