package com.claytonmuhoza.easybilling.data.local.stock

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.InventoryCount
import com.claytonmuhoza.easybilling.domain.stock.InventoryRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import java.math.BigDecimal

/**
 * Row of the `inventory_counts` table: what was counted of an item during the inventory in
 * progress. The quantity and the unit cost are stored as decimal text, never as floating numbers;
 * [unitCost] and [currency] are null when no purchase price was typed.
 */
@Entity(tableName = "inventory_counts")
internal data class InventoryCountEntity(
    @PrimaryKey val itemId: Long,
    val counted: String,
    val unitCost: String?,
    val currency: String?,
)

/** Row of the `stock_thresholds` table: the reorder threshold of an item, as decimal text. */
@Entity(tableName = "stock_thresholds")
internal data class StockThresholdEntity(@PrimaryKey val itemId: Long, val threshold: String)

/** Queries on the inventory in progress and on the reorder thresholds. */
@Dao
internal interface InventoryDao {
    @Query("SELECT * FROM inventory_counts ORDER BY itemId")
    suspend fun counts(): List<InventoryCountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCount(count: InventoryCountEntity)

    @Query("DELETE FROM inventory_counts WHERE itemId = :itemId")
    suspend fun removeCount(itemId: Long)

    @Query("DELETE FROM inventory_counts")
    suspend fun clearCounts()

    @Query("SELECT * FROM stock_thresholds")
    suspend fun thresholds(): List<StockThresholdEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveThreshold(threshold: StockThresholdEntity)

    @Query("DELETE FROM stock_thresholds WHERE itemId = :itemId")
    suspend fun removeThreshold(itemId: Long)
}

/** [InventoryRepository] backed by the local database. */
internal class RoomInventoryRepository(database: EasyBillingDatabase) : InventoryRepository {

    private val dao = database.inventoryDao()

    override suspend fun counts(): List<InventoryCount> = dao.counts().map { row ->
        val cost = row.unitCost?.let { Money.of(it, Currency.valueOf(checkNotNull(row.currency))) }
        InventoryCount(ItemId(row.itemId), BigDecimal(row.counted), cost)
    }

    override suspend fun save(count: InventoryCount) = dao.saveCount(
        InventoryCountEntity(
            itemId = count.itemId.value,
            counted = count.counted.toPlainString(),
            unitCost = count.unitCost?.toPlainString(),
            currency = count.unitCost?.currency?.name,
        ),
    )

    override suspend fun remove(itemId: ItemId) = dao.removeCount(itemId.value)

    override suspend fun clear() = dao.clearCounts()
}

/** [StockSettingsRepository] backed by the local database. */
internal class RoomStockSettingsRepository(database: EasyBillingDatabase) : StockSettingsRepository {

    private val dao = database.inventoryDao()

    override suspend fun thresholds(): Map<ItemId, BigDecimal> =
        dao.thresholds().associate { ItemId(it.itemId) to BigDecimal(it.threshold) }

    override suspend fun setThreshold(itemId: ItemId, threshold: BigDecimal?) =
        if (threshold == null) {
            dao.removeThreshold(itemId.value)
        } else {
            dao.saveThreshold(StockThresholdEntity(itemId.value, threshold.toPlainString()))
        }
}
