package com.claytonmuhoza.easybilling.data.local.stock

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.stock.DmcRepository
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import java.math.BigDecimal
import java.time.Instant

/**
 * Row of the `dmcs` table: a customs declaration read from the OBR. [fetchedAt] is in milliseconds.
 */
@Entity(tableName = "dmcs")
internal data class DmcEntity(@PrimaryKey val reference: String, val tin: String, val fetchedAt: Long)

/**
 * Row of the `dmc_lines` table: a line of a customs declaration. [quantity] is decimal text, never
 * a floating number, or null when the OBR gave no number.
 */
@Entity(tableName = "dmc_lines", primaryKeys = ["reference", "rank"])
internal data class DmcLineEntity(
    val reference: String,
    val rank: Int,
    val tariffHeading: String,
    val description: String,
    val quantity: String?,
    val packaging: String,
)

/** Queries on the customs declarations. */
@Dao
internal interface DmcDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(dmc: DmcEntity)

    @Insert
    suspend fun saveLines(lines: List<DmcLineEntity>)

    @Query("DELETE FROM dmc_lines WHERE reference = :reference")
    suspend fun removeLines(reference: String)

    @Query("SELECT * FROM dmcs WHERE reference = :reference")
    suspend fun find(reference: String): DmcEntity?

    @Query("SELECT * FROM dmcs ORDER BY fetchedAt DESC, reference")
    suspend fun all(): List<DmcEntity>

    @Query("SELECT * FROM dmc_lines WHERE reference = :reference ORDER BY rank")
    suspend fun linesOf(reference: String): List<DmcLineEntity>

    /** The entries of imported goods made for a declaration, the oldest first. */
    @Query("SELECT * FROM stock_movements WHERE dmcReference = :reference ORDER BY id")
    suspend fun ofDmc(reference: String): List<StockMovementEntity>
}

/** [DmcRepository] backed by the local database. */
internal class RoomDmcRepository(private val database: EasyBillingDatabase) : DmcRepository {

    private val dao = database.dmcDao()

    override suspend fun save(dmc: Dmc) = database.withTransaction {
        dao.save(DmcEntity(dmc.reference, dmc.tin, dmc.fetchedAt.toEpochMilli()))
        dao.removeLines(dmc.reference)
        dao.saveLines(
            dmc.lines.map { line ->
                DmcLineEntity(
                    reference = dmc.reference,
                    rank = line.rank,
                    tariffHeading = line.tariffHeading,
                    description = line.description,
                    quantity = line.quantity?.toPlainString(),
                    packaging = line.packaging,
                )
            },
        )
    }

    override suspend fun find(reference: String): Dmc? = dao.find(reference)?.toDomain()

    override suspend fun all(): List<Dmc> = dao.all().map { it.toDomain() }

    override suspend fun entriesOf(reference: String): List<StockMovement> =
        dao.ofDmc(reference).map { it.toDomain() }

    private suspend fun DmcEntity.toDomain() = Dmc(
        reference = reference,
        tin = tin,
        fetchedAt = Instant.ofEpochMilli(fetchedAt),
        lines = dao.linesOf(reference).map { row ->
            DmcLine(row.rank, row.tariffHeading, row.description, row.quantity?.let { BigDecimal(it) }, row.packaging)
        },
    )
}
