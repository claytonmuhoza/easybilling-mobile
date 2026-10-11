package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.ReadStock
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.ResolveStockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockStorage
import com.claytonmuhoza.easybilling.domain.stock.UnitSalePrice
import java.math.BigDecimal
import java.time.Instant

/** Stock movements kept in a map. */
class InMemoryStockRepository : StockRepository {
    private val movements = LinkedHashMap<MovementId, StockMovement>()
    private val statuses = mutableMapOf<MovementId, MovementStatus>()

    override suspend fun record(content: StockMovementContent): StockMovement {
        check(movements.values.none { it.content.reference == content.reference }) {
            "Reference ${content.reference} is already taken"
        }
        val movement = StockMovement(MovementId(movements.size + 1L), content)
        movements[movement.id] = movement
        statuses[movement.id] = MovementStatus.Pending
        return movement
    }

    override suspend fun find(id: MovementId): StockMovement? = movements[id]

    override suspend fun statusOf(id: MovementId): MovementStatus? = statuses[id]

    override suspend fun setStatus(id: MovementId, status: MovementStatus, at: Instant) {
        check(statuses.getValue(id).canBecome(status)) { "${statuses[id]} cannot become $status" }
        statuses[id] = status
    }

    override suspend fun count(): Long = movements.size.toLong()

    override suspend fun quantityOf(itemId: ItemId): BigDecimal = quantities()[itemId] ?: BigDecimal.ZERO

    override suspend fun quantities(): Map<ItemId, BigDecimal> = movements.values
        .groupBy { it.content.itemId }
        .mapValues { (_, moved) -> moved.sumOf { it.content.signedQuantity } }

    override suspend fun movementsOf(itemId: ItemId, limit: Int): List<StockMovement> =
        movements.values.filter { it.content.itemId == itemId }.reversed().take(limit)

    override suspend fun toResolve(): List<StockMovement> =
        movements.values.filter { statuses.getValue(it.id).toResolve }
}

/** The use cases of the stock on top of a [SaleFixture]: a business ready to sell, in memory. */
class StockFixture(val sale: SaleFixture = SaleFixture()) {
    val stock get() = sale.stock

    val record = RecordStockMovement(
        ApprovalAuthority(sale.access, sale.users, FixedPermissionPolicy),
        StockStorage(sale.items, sale.stock, sale.numbering, sale.ebmsSettings, sale.outbox, sale.audit),
        UnitSalePrice(sale.priceSale, sale.time),
        DirectTransactionRunner,
        sale.time,
    )
    val resolve = ResolveStockMovement(
        sale.access,
        sale.stock,
        sale.outbox,
        sale.audit,
        DirectTransactionRunner,
        sale.time,
    )
    val read = ReadStock(sale.items, sale.stock)
}
