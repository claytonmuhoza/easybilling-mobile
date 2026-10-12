package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.activeLoginTarget
import com.claytonmuhoza.easybilling.domain.identity.ApprovalAuthority
import com.claytonmuhoza.easybilling.domain.identity.FixedPermissionPolicy
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.stock.CloseInventory
import com.claytonmuhoza.easybilling.domain.stock.CountInventory
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcRepository
import com.claytonmuhoza.easybilling.domain.stock.EnterImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.FetchDmc
import com.claytonmuhoza.easybilling.domain.stock.InventoryCount
import com.claytonmuhoza.easybilling.domain.stock.InventoryRepository
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.ReadDmc
import com.claytonmuhoza.easybilling.domain.stock.ReadInventory
import com.claytonmuhoza.easybilling.domain.stock.ReadStock
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.ResolveStockMovement
import com.claytonmuhoza.easybilling.domain.stock.SetReorderThreshold
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import com.claytonmuhoza.easybilling.domain.stock.StockStorage
import com.claytonmuhoza.easybilling.domain.stock.UnitSalePrice
import com.claytonmuhoza.easybilling.domain.transmission.DispatchConnection
import com.claytonmuhoza.easybilling.domain.transmission.DispatchOutbox
import com.claytonmuhoza.easybilling.domain.transmission.DispatchStorage
import com.claytonmuhoza.easybilling.domain.transmission.ExponentialRetry
import java.math.BigDecimal
import java.time.Instant

/** Stock movements kept in a map. */
class InMemoryStockRepository : StockRepository {
    private val movements = LinkedHashMap<MovementId, StockMovement>()

    /** What each item had in stock before the test, without any movement telling of it. */
    val opening = mutableMapOf<ItemId, BigDecimal>()
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

    override suspend fun quantities(): Map<ItemId, BigDecimal> {
        val moved = movements.values
            .filter { statuses.getValue(it.id).counts }
            .groupBy { it.content.itemId }
            .mapValues { (_, counted) -> counted.sumOf { it.content.signedQuantity } }
        return (opening.keys + moved.keys).associateWith { item ->
            (opening[item] ?: BigDecimal.ZERO) + (moved[item] ?: BigDecimal.ZERO)
        }
    }

    override suspend fun lastEntryCosts(): Map<ItemId, Money> = movements.values
        .filter { it.content.type in setOf(MovementType.EN, MovementType.EI) && statuses.getValue(it.id).counts }
        .associate { it.content.itemId to it.content.unitCost }

    /** @return every movement, the oldest first. */
    fun all(): List<StockMovement> = movements.values.toList()

    override suspend fun ofInvoice(invoiceId: InvoiceId): List<StockMovement> =
        movements.values.filter { it.content.invoiceId == invoiceId }

    override suspend fun movementsOf(itemId: ItemId, limit: Int): List<StockMovement> =
        movements.values.filter { it.content.itemId == itemId }.reversed().take(limit)

    override suspend fun toResolve(): List<StockMovement> =
        movements.values.filter { statuses.getValue(it.id).toResolve }
}

/** The inventory in progress kept in a map. */
class InMemoryInventoryRepository : InventoryRepository {
    private val kept = LinkedHashMap<ItemId, InventoryCount>()

    override suspend fun counts(): List<InventoryCount> = kept.values.toList()

    override suspend fun save(count: InventoryCount) {
        kept[count.itemId] = count
    }

    override suspend fun remove(itemId: ItemId) {
        kept.remove(itemId)
    }

    override suspend fun clear() = kept.clear()
}

/** The customs declarations kept in a map, in front of the movements of [stock]. */
class InMemoryDmcRepository(private val stock: InMemoryStockRepository) : DmcRepository {
    private val kept = LinkedHashMap<String, Dmc>()

    override suspend fun save(dmc: Dmc) {
        kept.remove(dmc.reference)
        kept[dmc.reference] = dmc
    }

    override suspend fun find(reference: String): Dmc? = kept[reference]

    override suspend fun all(): List<Dmc> = kept.values.reversed()

    override suspend fun entriesOf(reference: String): List<StockMovement> =
        stock.all().filter { it.content.imported?.dmcReference == reference }
}

/** The reorder thresholds kept in a map. */
class InMemoryStockSettingsRepository : StockSettingsRepository {
    private val kept = mutableMapOf<ItemId, BigDecimal>()

    override suspend fun thresholds(): Map<ItemId, BigDecimal> = kept.toMap()

    override suspend fun setThreshold(itemId: ItemId, threshold: BigDecimal?) {
        if (threshold == null) kept.remove(itemId) else kept[itemId] = threshold
    }
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
    val inventory = InMemoryInventoryRepository()
    val settings = InMemoryStockSettingsRepository()
    private val storage =
        StockStorage(sale.items, sale.stock, sale.numbering, sale.ebmsSettings, sale.outbox, sale.audit)
    val read = ReadStock(sale.items, sale.stock, settings)
    val readInventory = ReadInventory(sale.items, sale.stock, inventory)
    val count = CountInventory(sale.access, sale.items, inventory)
    val closeInventory = CloseInventory(
        ApprovalAuthority(sale.access, sale.users, FixedPermissionPolicy),
        storage,
        inventory,
        UnitSalePrice(sale.priceSale, sale.time),
        DirectTransactionRunner,
        sale.time,
    )
    val setThreshold = SetReorderThreshold(sale.access, sale.items, settings, sale.audit)
    val dmcs = InMemoryDmcRepository(sale.stock)
    val readDmc = ReadDmc(dmcs, sale.stock, storage)
    val enterImported = EnterImportedGoods(sale.access, storage, dmcs, DirectTransactionRunner, sale.time)

    /** @return the reading of a customs declaration, in front of [gateway]. */
    fun fetchDmc(gateway: FakeObrGateway) = FetchDmc(
        sale.access,
        sale.profiles,
        DispatchConnection({ sale.ebmsSettings.activeLoginTarget(sale.vault) }, FixedTokenProvider(), gateway),
        dmcs,
        sale.time,
    )

    /** @return the send queue of the business, in front of [gateway]. */
    fun dispatcher(gateway: FakeObrGateway) = DispatchOutbox(
        DispatchStorage(
            sale.outbox,
            sale.invoices,
            InMemoryReceiptArchive(),
            sale.profiles,
            sale.cancellations,
            sale.stock,
            sale.invoiceStock,
            sale.audit,
            DirectTransactionRunner,
        ),
        DispatchConnection({ sale.ebmsSettings.activeLoginTarget(sale.vault) }, FixedTokenProvider(), gateway),
        ExponentialRetry,
        sale.time,
    )
}
