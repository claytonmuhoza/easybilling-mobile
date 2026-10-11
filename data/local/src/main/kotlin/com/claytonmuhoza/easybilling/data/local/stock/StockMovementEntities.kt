package com.claytonmuhoza.easybilling.data.local.stock

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.stock.MovementDirection
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import java.math.BigDecimal
import java.time.Instant

/**
 * Row of the `stock_movements` table: a recorded stock movement. The table is append only (see
 * `AppendOnlyTables`) and the reference is unique at the database level. The quantity and the unit
 * cost are stored as decimal text, never as floating numbers; [signedQuantity] is the quantity as
 * it moves the stock, negative for an exit, also as decimal text. [occurredAt] is in milliseconds.
 * [invoiceId] is the invoice or the credit note the movement was written with, if any.
 */
@Entity(
    tableName = "stock_movements",
    indices = [Index("reference", unique = true), Index("itemId"), Index("invoiceId")],
)
internal data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val reference: String,
    val itemId: Long,
    val itemCode: String,
    val designation: String,
    val unit: String,
    val type: String,
    val quantity: String,
    val signedQuantity: String,
    val unitCost: String,
    val currency: String,
    val description: String,
    val invoiceRef: String?,
    val occurredAt: Long,
    val environment: String,
    val recordedBy: Long,
    val approvedBy: Long?,
    val invoiceId: Long?,
)

/**
 * Row of the `stock_movement_statuses` table: where a movement stands with the OBR. It is the only
 * thing of a movement that changes. [message] is the refusal of the OBR; [changedAt] is in
 * milliseconds.
 */
@Entity(tableName = "stock_movement_statuses")
internal data class StockMovementStatusEntity(
    @PrimaryKey val movementId: Long,
    val status: String,
    val message: String,
    val changedAt: Long,
)

/** Queries on the stock movements. */
@Dao
internal interface StockMovementDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(movement: StockMovementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeStatus(status: StockMovementStatusEntity)

    @Query("SELECT * FROM stock_movements WHERE id = :id")
    suspend fun find(id: Long): StockMovementEntity?

    @Query("SELECT * FROM stock_movement_statuses WHERE movementId = :movementId")
    suspend fun status(movementId: Long): StockMovementStatusEntity?

    @Query("SELECT COUNT(*) FROM stock_movements")
    suspend fun count(): Long

    /**
     * The quantities are summed by the application: the database only sums floating numbers. A
     * movement whose status is [excluded] does not count.
     */
    @Query(
        """
        SELECT m.signedQuantity FROM stock_movements m JOIN stock_movement_statuses s ON s.movementId = m.id
        WHERE m.itemId = :itemId AND s.status != :excluded
        """,
    )
    suspend fun signedQuantitiesOf(itemId: Long, excluded: String): List<String>

    @Query(
        """
        SELECT m.itemId, m.signedQuantity FROM stock_movements m
        JOIN stock_movement_statuses s ON s.movementId = m.id WHERE s.status != :excluded
        """,
    )
    suspend fun signedQuantities(excluded: String): List<ItemQuantityRow>

    /** The entries of [types] that count, the oldest first: the last one of an item gives its last cost. */
    @Query(
        """
        SELECT m.* FROM stock_movements m JOIN stock_movement_statuses s ON s.movementId = m.id
        WHERE m.type IN (:types) AND s.status != :excluded ORDER BY m.id
        """,
    )
    suspend fun ofTypes(types: List<String>, excluded: String): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movements WHERE invoiceId = :invoiceId ORDER BY id")
    suspend fun ofInvoice(invoiceId: Long): List<StockMovementEntity>

    @Query("SELECT * FROM stock_movements WHERE itemId = :itemId ORDER BY id DESC LIMIT :limit")
    suspend fun movementsOf(itemId: Long, limit: Int): List<StockMovementEntity>

    @Query(
        """
        SELECT m.* FROM stock_movements m JOIN stock_movement_statuses s ON s.movementId = m.id
        WHERE s.status IN (:statuses) ORDER BY m.id
        """,
    )
    suspend fun withStatus(statuses: List<String>): List<StockMovementEntity>
}

/** What a movement does to the stock of an item. */
internal data class ItemQuantityRow(val itemId: Long, val signedQuantity: String)

/** [StockRepository] backed by the local database. */
internal class RoomStockRepository(private val database: EasyBillingDatabase) : StockRepository {

    private val dao = database.stockMovementDao()

    override suspend fun record(content: StockMovementContent): StockMovement = database.withTransaction {
        val id = dao.insert(content.toEntity())
        dao.writeStatus(MovementStatus.Pending.toEntity(id, content.occurredAt))
        StockMovement(MovementId(id), content)
    }

    override suspend fun find(id: MovementId): StockMovement? = dao.find(id.value)?.toDomain()

    override suspend fun statusOf(id: MovementId): MovementStatus? = dao.status(id.value)?.toDomain()

    override suspend fun setStatus(id: MovementId, status: MovementStatus, at: Instant) = database.withTransaction {
        val current = checkNotNull(dao.status(id.value)) { "No movement ${id.value}" }.toDomain()
        check(current.canBecome(status)) { "A movement ${current.code} cannot become ${status.code}" }
        dao.writeStatus(status.toEntity(id.value, at))
    }

    override suspend fun count(): Long = dao.count()

    override suspend fun quantityOf(itemId: ItemId): BigDecimal =
        dao.signedQuantitiesOf(itemId.value, MovementStatus.Voided.code).sumOf { BigDecimal(it) }

    override suspend fun quantities(): Map<ItemId, BigDecimal> = dao.signedQuantities(MovementStatus.Voided.code)
        .groupBy({ ItemId(it.itemId) }, { BigDecimal(it.signedQuantity) })
        .mapValues { (_, quantities) -> quantities.sumOf { it } }

    override suspend fun movementsOf(itemId: ItemId, limit: Int): List<StockMovement> =
        dao.movementsOf(itemId.value, limit).map { it.toDomain() }

    override suspend fun lastEntryCosts(): Map<ItemId, Money> =
        dao.ofTypes(listOf(MovementType.EN.name, MovementType.EI.name), MovementStatus.Voided.code)
            .associate { ItemId(it.itemId) to Money.of(it.unitCost, Currency.valueOf(it.currency)) }

    override suspend fun ofInvoice(invoiceId: InvoiceId): List<StockMovement> =
        dao.ofInvoice(invoiceId.value).map { it.toDomain() }

    override suspend fun toResolve(): List<StockMovement> =
        dao.withStatus(listOf(REJECTED, MovementStatus.ToConfirm.code)).map { it.toDomain() }
}

/** Code of [MovementStatus.Rejected], the only status that is not an object. */
private const val REJECTED = "REJECTED"

private fun StockMovementContent.toEntity() = StockMovementEntity(
    id = 0,
    reference = reference,
    itemId = itemId.value,
    itemCode = itemCode,
    designation = designation,
    unit = unit,
    type = type.name,
    quantity = quantity.toPlainString(),
    signedQuantity = signedQuantity.toPlainString(),
    unitCost = unitCost.toPlainString(),
    currency = unitCost.currency.name,
    description = description,
    invoiceRef = invoiceRef,
    occurredAt = occurredAt.toEpochMilli(),
    environment = environment.name,
    recordedBy = recordedBy.value,
    approvedBy = approvedBy?.value,
    invoiceId = invoiceId?.value,
)

private fun StockMovementEntity.toDomain(): StockMovement {
    val movementType = MovementType.valueOf(type)
    check((movementType.direction == MovementDirection.EXIT) == signedQuantity.startsWith("-")) {
        "The movement $reference does not move the stock as its type says"
    }
    return StockMovement(
        id = MovementId(id),
        content = StockMovementContent(
            reference = reference,
            itemId = ItemId(itemId),
            itemCode = itemCode,
            designation = designation,
            unit = unit,
            type = movementType,
            quantity = Quantity.of(quantity),
            unitCost = Money.of(unitCost, Currency.valueOf(currency)),
            description = description,
            invoiceRef = invoiceRef,
            occurredAt = Instant.ofEpochMilli(occurredAt),
            environment = EbmsEnvironment.valueOf(environment),
            recordedBy = UserId(recordedBy),
            approvedBy = approvedBy?.let { UserId(it) },
            invoiceId = invoiceId?.let { InvoiceId(it) },
        ),
    )
}

private fun MovementStatus.toEntity(movementId: Long, at: Instant) = StockMovementStatusEntity(
    movementId = movementId,
    status = code,
    message = (this as? MovementStatus.Rejected)?.message.orEmpty(),
    changedAt = at.toEpochMilli(),
)

private fun StockMovementStatusEntity.toDomain(): MovementStatus = when (status) {
    MovementStatus.Pending.code -> MovementStatus.Pending
    MovementStatus.Declared.code -> MovementStatus.Declared
    MovementStatus.ToConfirm.code -> MovementStatus.ToConfirm
    MovementStatus.Voided.code -> MovementStatus.Voided
    REJECTED -> MovementStatus.Rejected(message)
    else -> error("Unknown movement status $status")
}
