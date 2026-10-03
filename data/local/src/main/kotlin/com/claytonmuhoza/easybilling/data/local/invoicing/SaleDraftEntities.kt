package com.claytonmuhoza.easybilling.data.local.invoicing

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.invoicing.Discount
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountKind
import com.claytonmuhoza.easybilling.domain.invoicing.DraftId
import com.claytonmuhoza.easybilling.domain.invoicing.DraftLine
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraft
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import java.time.Instant

/** Row of the `sale_drafts` table: a sale that is not issued. [updatedAt] is in milliseconds. */
@Entity(tableName = "sale_drafts")
internal data class SaleDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val customerId: Long?,
    val paymentType: String,
    val held: Boolean,
    val updatedAt: Long,
)

/**
 * Row of the `sale_draft_lines` table, deleted with its sale. Numbers are stored as decimal text,
 * never as floating numbers. The four free amounts are the taxes typed on the line; the VAT is
 * always a percentage.
 */
@Entity(
    tableName = "sale_draft_lines",
    primaryKeys = ["draftId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = SaleDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SaleDraftLineEntity(
    val draftId: Long,
    val position: Int,
    val itemId: Long,
    val quantity: String,
    val discountKind: String?,
    val discountValue: String?,
    val freeConsumptionTax: String?,
    val freeFlatRateWithholding: String?,
    val freeOtt: String?,
    val freeTsce: String?,
)

/** Queries on the sales that are not issued. */
@Dao
internal interface SaleDraftDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(draft: SaleDraftEntity): Long

    @Update
    suspend fun update(draft: SaleDraftEntity)

    @Insert
    suspend fun insertLines(lines: List<SaleDraftLineEntity>)

    @Query("DELETE FROM sale_draft_lines WHERE draftId = :draftId")
    suspend fun deleteLines(draftId: Long)

    @Query("DELETE FROM sale_drafts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM sale_drafts WHERE id = :id")
    suspend fun find(id: Long): SaleDraftEntity?

    @Query("SELECT * FROM sale_drafts WHERE held = 0 ORDER BY id LIMIT 1")
    suspend fun current(): SaleDraftEntity?

    @Query("SELECT * FROM sale_drafts WHERE held = 1 ORDER BY updatedAt DESC, id DESC")
    suspend fun held(): List<SaleDraftEntity>

    @Query("SELECT * FROM sale_draft_lines WHERE draftId = :draftId ORDER BY position")
    suspend fun lines(draftId: Long): List<SaleDraftLineEntity>
}

/** [SaleDraftRepository] backed by the local database. */
internal class RoomSaleDraftRepository(private val database: EasyBillingDatabase) : SaleDraftRepository {

    private val dao = database.saleDraftDao()

    override suspend fun create(now: Instant): SaleDraft {
        val draft = SaleDraft(DraftId(0), updatedAt = now)
        return draft.copy(id = DraftId(dao.insert(draft.toEntity())))
    }

    override suspend fun find(id: DraftId): SaleDraft? = database.withTransaction { dao.find(id.value)?.toDomain() }

    override suspend fun current(): SaleDraft? = database.withTransaction { dao.current()?.toDomain() }

    override suspend fun held(): List<SaleDraft> = database.withTransaction { dao.held().map { it.toDomain() } }

    override suspend fun save(draft: SaleDraft) = database.withTransaction {
        dao.update(draft.toEntity())
        dao.deleteLines(draft.id.value)
        dao.insertLines(draft.lines.mapIndexed { position, line -> line.toEntity(draft.id, position) })
    }

    override suspend fun delete(id: DraftId) = dao.delete(id.value)

    private fun SaleDraft.toEntity() =
        SaleDraftEntity(id.value, customerId?.value, paymentType.name, held, updatedAt.toEpochMilli())

    private suspend fun SaleDraftEntity.toDomain() = SaleDraft(
        id = DraftId(id),
        customerId = customerId?.let { CustomerId(it) },
        paymentType = PaymentType.valueOf(paymentType),
        lines = dao.lines(id).map { it.toDomain() },
        held = held,
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

    private fun DraftLine.toEntity(draftId: DraftId, position: Int) = SaleDraftLineEntity(
        draftId = draftId.value,
        position = position,
        itemId = itemId.value,
        quantity = quantity.toPlainString(),
        discountKind = discount.kind()?.name,
        discountValue = discount.value(),
        freeConsumptionTax = freeAmounts[TaxKind.CONSUMPTION_TAX]?.toPlainString(),
        freeFlatRateWithholding = freeAmounts[TaxKind.FLAT_RATE_WITHHOLDING]?.toPlainString(),
        freeOtt = freeAmounts[TaxKind.OTT]?.toPlainString(),
        freeTsce = freeAmounts[TaxKind.TSCE]?.toPlainString(),
    )

    private fun SaleDraftLineEntity.toDomain() = DraftLine(
        itemId = ItemId(itemId),
        quantity = Quantity.of(quantity),
        discount = discountOf(discountKind, discountValue),
        freeAmounts = listOfNotNull(
            freeConsumptionTax?.let { TaxKind.CONSUMPTION_TAX to it },
            freeFlatRateWithholding?.let { TaxKind.FLAT_RATE_WITHHOLDING to it },
            freeOtt?.let { TaxKind.OTT to it },
            freeTsce?.let { TaxKind.TSCE to it },
        ).associate { (kind, amount) -> kind to Money.of(amount, Currency.BIF) },
    )
}

/** @return how the discount is given, or null for no discount. */
internal fun Discount?.kind(): DiscountKind? = when (this) {
    null -> null
    is Discount.Percent -> DiscountKind.PERCENT
    is Discount.PerUnit -> DiscountKind.PER_UNIT
}

/** @return the percentage or the amount of the discount as decimal text, or null for no discount. */
internal fun Discount?.value(): String? = when (this) {
    null -> null
    is Discount.Percent -> rate.stripTrailingZeros().toPlainString()
    is Discount.PerUnit -> amount.stripTrailingZeros().toPlainString()
}

/** @return the discount stored as [kind] and [value], or null when there is none. */
internal fun discountOf(kind: String?, value: String?): Discount? {
    val number = value?.let { BigDecimal(it) } ?: return null
    return when (kind?.let { DiscountKind.valueOf(it) }) {
        null -> null
        DiscountKind.PERCENT -> Discount.Percent(number)
        DiscountKind.PER_UNIT -> Discount.PerUnit(number)
    }
}
