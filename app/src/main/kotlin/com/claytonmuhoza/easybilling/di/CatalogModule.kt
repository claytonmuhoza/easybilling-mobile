package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ArchiveItem
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.catalog.ReadItemRules
import com.claytonmuhoza.easybilling.domain.catalog.SuggestItemCode
import com.claytonmuhoza.easybilling.domain.catalog.UpdateItem
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the catalogue: the items and their use cases. */
@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    /** @return the items kept in the local database. */
    @Provides
    fun itemRepository(storage: LocalStorage): ItemRepository = storage.items

    /** @return the use case reading what the rules of an item depend on. */
    @Provides
    fun readItemRules(
        profiles: TaxProfileRepository,
        numbering: NumberingRepository,
        taxes: TaxRepository,
    ): ReadItemRules = ReadItemRules(profiles, numbering, taxes)

    /** @return the use case proposing the code of a new item. */
    @Provides
    fun suggestItemCode(items: ItemRepository): SuggestItemCode = SuggestItemCode(items)

    /** @return the use case creating an item. */
    @Provides
    fun createItem(
        access: AccessControl,
        items: ItemRepository,
        readRules: ReadItemRules,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): CreateItem = CreateItem(access, items, readRules, auditLog, transactions)

    /** @return the use case changing an item. */
    @Provides
    fun updateItem(
        access: AccessControl,
        items: ItemRepository,
        readRules: ReadItemRules,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): UpdateItem = UpdateItem(access, items, readRules, auditLog, transactions)

    /** @return the use case archiving an item. */
    @Provides
    fun archiveItem(
        access: AccessControl,
        items: ItemRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
    ): ArchiveItem =
        ArchiveItem(access, items, auditLog, transactions)
}
