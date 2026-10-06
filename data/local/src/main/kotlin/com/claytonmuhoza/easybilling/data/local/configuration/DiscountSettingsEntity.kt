package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettings
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettingsRepository

/**
 * The only row of the `discount_settings` table. [cashierCap] is decimal text, never a floating
 * number, and null for no cap.
 */
@Entity(tableName = "discount_settings")
internal class DiscountSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val cashierCap: String?,
) {
    companion object {
        /** Identifier of the only row. */
        const val SINGLE_ROW = 1
    }
}

/** Queries on the `discount_settings` table. */
@Dao
internal interface DiscountSettingsDao {
    @Query("SELECT * FROM discount_settings WHERE id = ${DiscountSettingsEntity.SINGLE_ROW}")
    suspend fun read(): DiscountSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(settings: DiscountSettingsEntity)
}

/** [DiscountSettingsRepository] backed by the local database. */
internal class RoomDiscountSettingsRepository(private val dao: DiscountSettingsDao) : DiscountSettingsRepository {

    override suspend fun read(): DiscountSettings =
        DiscountSettings(cashierCap = dao.read()?.cashierCap?.toBigDecimal())

    override suspend fun write(settings: DiscountSettings) =
        dao.write(DiscountSettingsEntity(cashierCap = settings.cashierCap?.toPlainString()))
}
