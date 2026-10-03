package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import java.math.BigDecimal
import java.time.LocalDate

/** Row of the `taxes` table. */
@Entity(tableName = "taxes")
internal class TaxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String,
    val name: String,
    val archived: Boolean,
)

/**
 * Row of the `tax_versions` table: one rate of a tax. A tax cannot have two rates starting the
 * same day. Amounts are stored as decimal text, never as a floating number.
 */
@Entity(
    tableName = "tax_versions",
    primaryKeys = ["taxId", "validFromEpochDay"],
    foreignKeys = [ForeignKey(entity = TaxEntity::class, parentColumns = ["id"], childColumns = ["taxId"])],
)
internal class TaxVersionEntity(
    val taxId: Long,
    val validFromEpochDay: Long,
    val mode: String,
    val value: String?,
    val base: String?,
)

/** Queries on the `taxes` and `tax_versions` tables. A rate is never updated nor deleted. */
@Dao
internal interface TaxDao {
    @Query("SELECT * FROM taxes ORDER BY id")
    suspend fun taxes(): List<TaxEntity>

    @Query("SELECT * FROM taxes WHERE id = :id")
    suspend fun tax(id: Long): TaxEntity?

    @Query("SELECT * FROM tax_versions ORDER BY taxId, validFromEpochDay")
    suspend fun versions(): List<TaxVersionEntity>

    @Query("SELECT * FROM tax_versions WHERE taxId = :taxId ORDER BY validFromEpochDay")
    suspend fun versionsOf(taxId: Long): List<TaxVersionEntity>

    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(tax: TaxEntity): Long

    @Insert
    suspend fun insert(version: TaxVersionEntity)

    @Query("UPDATE taxes SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)
}

/** [TaxRepository] backed by the local database. */
internal class RoomTaxRepository(private val dao: TaxDao) : TaxRepository {

    override suspend fun all(): List<TaxDefinition> {
        val versions = dao.versions().groupBy { it.taxId }
        return dao.taxes().map { it.toDomain(versions[it.id].orEmpty()) }
    }

    override suspend fun find(id: TaxId): TaxDefinition? = dao.tax(id.value)?.let { it.toDomain(dao.versionsOf(it.id)) }

    override suspend fun create(kind: TaxKind, name: String, version: TaxVersion): TaxDefinition {
        val id = dao.insert(TaxEntity(id = 0, kind = kind.name, name = name, archived = false))
        dao.insert(version.toEntity(id))
        return TaxDefinition(TaxId(id), kind, name, listOf(version))
    }

    override suspend fun addVersion(id: TaxId, version: TaxVersion) = dao.insert(version.toEntity(id.value))

    override suspend fun setArchived(id: TaxId, archived: Boolean) = dao.setArchived(id.value, archived)

    private fun TaxEntity.toDomain(versions: List<TaxVersionEntity>) = TaxDefinition(
        id = TaxId(id),
        kind = TaxKind.valueOf(kind),
        name = name,
        versions = versions.map { it.toDomain() },
        archived = archived,
    )

    private fun TaxVersionEntity.toDomain() = TaxVersion(
        validFrom = LocalDate.ofEpochDay(validFromEpochDay),
        computation = when (mode) {
            MODE_PERCENTAGE -> TaxComputation.Percentage(
                rate = BigDecimal(checkNotNull(value)),
                base = PercentageBase.valueOf(checkNotNull(base)),
            )
            MODE_PER_UNIT -> TaxComputation.PerUnit(BigDecimal(checkNotNull(value)))
            else -> TaxComputation.FreeAmount
        },
    )

    private fun TaxVersion.toEntity(taxId: Long): TaxVersionEntity {
        val day = validFrom.toEpochDay()
        return when (val tax = computation) {
            is TaxComputation.Percentage ->
                TaxVersionEntity(taxId, day, MODE_PERCENTAGE, tax.rate.toPlainString(), tax.base.name)
            is TaxComputation.PerUnit -> TaxVersionEntity(taxId, day, MODE_PER_UNIT, tax.amount.toPlainString(), null)
            TaxComputation.FreeAmount -> TaxVersionEntity(taxId, day, MODE_FREE_AMOUNT, null, null)
        }
    }

    private companion object {
        const val MODE_PERCENTAGE = "PERCENTAGE"
        const val MODE_PER_UNIT = "PER_UNIT"
        const val MODE_FREE_AMOUNT = "FREE_AMOUNT"
    }
}
