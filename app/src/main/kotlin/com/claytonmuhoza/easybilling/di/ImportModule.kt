package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.stock.DmcRepository
import com.claytonmuhoza.easybilling.domain.stock.EnterImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.FetchDmc
import com.claytonmuhoza.easybilling.domain.stock.ReadDmc
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockStorage
import com.claytonmuhoza.easybilling.domain.transmission.DispatchConnection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the imported goods: the customs declarations and their entry into the stock (UC-F03, F04). */
@Module
@InstallIn(SingletonComponent::class)
object ImportModule {

    /** @return the customs declarations kept in the local database. */
    @Provides
    fun dmcRepository(storage: LocalStorage): DmcRepository = storage.dmcs

    /** @return the use case reading a customs declaration from the OBR. */
    @Provides
    fun fetchDmc(
        access: AccessControl,
        profiles: TaxProfileRepository,
        connection: DispatchConnection,
        dmcs: DmcRepository,
        time: TimeProvider,
    ): FetchDmc = FetchDmc(access, profiles, connection, dmcs, time)

    /** @return the use case reading a declaration kept on the device with what was entered of it. */
    @Provides
    fun readDmc(dmcs: DmcRepository, stock: StockRepository, storage: StockStorage): ReadDmc =
        ReadDmc(dmcs, stock, storage)

    /** @return the use case entering imported goods into the stock. */
    @Provides
    fun enterImportedGoods(
        access: AccessControl,
        storage: StockStorage,
        dmcs: DmcRepository,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): EnterImportedGoods = EnterImportedGoods(access, storage, dmcs, transactions, time)
}
