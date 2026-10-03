package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import java.time.Instant

/** The only row of the `numbering_settings` table. */
@Entity(tableName = "numbering_settings")
internal class NumberingSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val template: String,
    val reset: String,
    val multipleDevices: Boolean,
    val deviceCode: String,
    val lockedAtMillis: Long?,
) {
    companion object {
        /** Identifier of the only row. */
        const val SINGLE_ROW = 1
    }
}

/** Row of the `number_counters` table: the last counter given for a type of document in a period. */
@Entity(tableName = "number_counters", primaryKeys = ["documentType", "period"])
internal class NumberCounterEntity(
    val documentType: String,
    val period: Int,
    val lastCounter: Long,
)

/** Queries on the `numbering_settings` and `number_counters` tables. */
@Dao
internal interface NumberingDao {
    @Query("SELECT * FROM numbering_settings WHERE id = ${NumberingSettingsEntity.SINGLE_ROW}")
    suspend fun settings(): NumberingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(settings: NumberingSettingsEntity)

    @Query("SELECT lastCounter FROM number_counters WHERE documentType = :documentType AND period = :period")
    suspend fun lastCounter(documentType: String, period: Int): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(counter: NumberCounterEntity)
}

/** [NumberingRepository] backed by the local database. */
internal class RoomNumberingRepository(private val dao: NumberingDao) : NumberingRepository {

    override suspend fun settings(): NumberingSettings? = dao.settings()?.let {
        NumberingSettings(
            template = it.template,
            reset = CounterReset.valueOf(it.reset),
            multipleDevices = it.multipleDevices,
            deviceCode = it.deviceCode,
            lockedAt = it.lockedAtMillis?.let(Instant::ofEpochMilli),
        )
    }

    override suspend fun save(settings: NumberingSettings) = dao.write(
        NumberingSettingsEntity(
            template = settings.template,
            reset = settings.reset.name,
            multipleDevices = settings.multipleDevices,
            deviceCode = settings.deviceCode,
            lockedAtMillis = settings.lockedAt?.toEpochMilli(),
        ),
    )

    override suspend fun lastCounter(type: DocumentType, period: Int): Long = dao.lastCounter(type.name, period) ?: 0

    override suspend fun setLastCounter(type: DocumentType, period: Int, counter: Long) =
        dao.write(NumberCounterEntity(type.name, period, counter))
}
