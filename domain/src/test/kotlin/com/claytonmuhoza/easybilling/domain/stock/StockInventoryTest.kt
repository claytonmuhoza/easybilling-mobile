package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.testing.StockFixture
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The inventory, the reorder threshold and the value of the stock (UC-F08, F10, D55): what is
 * counted moves nothing until the inventory is closed, each gap then becomes an adjustment, and
 * the stock is worth its last purchase prices.
 */
class StockInventoryTest {

    private val fixture = StockFixture()
    private val sale = fixture.sale
    private val alice = sale.alice

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private suspend fun account(vararg roles: Role): UserId {
        val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
        return sale.users.create(NewUser("User ${sale.users.count() + 1}", roles.toSet(), secret)).id
    }

    /** An item kept in stock at 1 000 without taxes, 1 180 with the VAT. */
    private suspend fun item(code: String): Item {
        if (sale.profiles.current() == null) sale.ready()
        return sale.items.findByCode(code) ?: sale.item(code, "1000", stockTracked = true)
    }

    /** Enters [quantity] of [item] bought at [cost]. */
    private suspend fun entered(
        item: Item,
        quantity: String,
        cost: String = "800",
        type: MovementType = MovementType.EN,
    ) {
        val form = StockMovementForm(item.id, type, quantity, unitCost = cost)
        assertIs<RecordStockMovement.Result.Recorded>(fixture.record(form, alice))
    }

    private suspend fun counted(item: Item, quantity: String, cost: String = "") =
        fixture.count(item.id, quantity, cost, alice)

    private suspend fun closed(note: String = ""): List<StockMovement> =
        assertIs<CloseInventory.Result.Closed>(fixture.closeInventory(note, alice)).adjustments

    private fun assertQuantity(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Quantity: $actual")

    @Test
    fun `what is counted moves nothing until the inventory is closed`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")
        val movements = fixture.stock.count()

        assertEquals(CountInventory.Result.Saved(InventoryCount(soap.id, BigDecimal("7.5"))), counted(soap, "7,5"))

        assertQuantity("10", fixture.stock.quantityOf(soap.id))
        assertEquals(movements, fixture.stock.count())
        val line = fixture.readInventory(query = "", limit = 100).single()
        assertQuantity("10", line.expected)
        assertQuantity("-2.5", line.gap)
        assertEquals(bif("800"), line.lastCost)
        assertEquals(false, line.missingCost)
    }

    @Test
    fun `a count of zero is a count, an empty one is none, and what is not a quantity is refused`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")

        assertEquals(CountInventory.Result.Saved(InventoryCount(soap.id, BigDecimal("120"))), counted(soap, "120.00"))
        assertEquals(CountInventory.Result.Saved(InventoryCount(soap.id, BigDecimal.ZERO)), counted(soap, "0.0"))
        for (wrong in listOf("-1", "abc", "1.234")) {
            val invalid = CountInventory.Result.Invalid(setOf(CountProblem.QUANTITY_INVALID))
            assertEquals(invalid, counted(soap, wrong), wrong)
        }
        assertEquals(CountInventory.Result.Invalid(setOf(CountProblem.COST_INVALID)), counted(soap, "12", cost = "0"))
        assertQuantity("0", fixture.inventory.counts().single().counted)

        assertEquals(CountInventory.Result.Cleared, counted(soap, " "))
        assertEquals(emptyList(), fixture.inventory.counts())
        assertNull(fixture.readInventory(query = "", limit = 100).single().gap)
    }

    @Test
    fun `an item that is not kept in stock or is archived is not counted`() = runTest {
        item("ART-0001")
        val service = sale.item("SRV-0001", "5000")
        val archived = item("ART-0009")
        sale.items.setArchived(archived.id, true)

        assertEquals(CountInventory.Result.Refused(StockProblem.ITEM_NOT_TRACKED), counted(service, "1"))
        assertEquals(CountInventory.Result.Refused(StockProblem.ITEM_ARCHIVED), counted(archived, "1"))
        assertEquals(listOf("ART-0001"), fixture.readInventory(query = "", limit = 100).map { it.item.code })
    }

    @Test
    fun `closing adjusts each gap, up at the last purchase price and down at the sale price`() = runTest {
        val soap = item("ART-0001")
        val sugar = item("ART-0002")
        val oil = item("ART-0003")
        val rice = item("ART-0004")
        entered(soap, "10", cost = "800")
        entered(sugar, "5", cost = "650")
        entered(oil, "4")
        entered(rice, "9")
        counted(soap, "8")
        counted(sugar, "6.5")
        counted(oil, "4")
        sale.audit.events.clear()
        val queued = sale.outbox.pending().size

        val adjustments = closed(note = " Comptage du soir ")

        val (down, up) = adjustments.map { it.content }
        assertEquals(MovementType.SAJ, down.type)
        assertEquals(soap.id, down.itemId)
        assertEquals(BigDecimal("2"), down.quantity.value)
        // The unit sale price, taxes included.
        assertEquals(bif("1180"), down.unitCost)
        assertEquals("Inventaire du 03/10/2026 Comptage du soir", down.description)
        assertEquals(MovementType.EAJ, up.type)
        assertEquals(sugar.id, up.itemId)
        assertEquals(BigDecimal("1.5"), up.quantity.value)
        assertEquals(bif("650"), up.unitCost)
        assertEquals(listOf("000005", "000006"), listOf(down.reference, up.reference))
        assertEquals(alice, down.recordedBy)
        assertNull(down.approvedBy)
        assertNull(down.invoiceId)
        // The stock is what was counted; what was not counted did not move.
        assertQuantity("8", fixture.stock.quantityOf(soap.id))
        assertQuantity("6.5", fixture.stock.quantityOf(sugar.id))
        assertQuantity("4", fixture.stock.quantityOf(oil.id))
        assertQuantity("9", fixture.stock.quantityOf(rice.id))
        assertEquals(emptyList(), fixture.inventory.counts())
        assertEquals(
            adjustments.map { OutboxOperation.SendStockMovement(it.id) },
            sale.outbox.pending().drop(queued).map { it.operation },
        )
        assertEquals(AuditEvent.InventoryClosed(alice, adjustments = 2, approver = null), sale.audit.events.last())
        assertEquals("adjustments=2", sale.audit.events.last().details)
    }

    @Test
    fun `the gap is the one of the moment the inventory is closed`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")
        counted(soap, "8")
        // Two soaps leave the stock while the count goes on.
        val exit = StockMovementForm(soap.id, MovementType.ST, "2", description = "Vers le depot")
        assertIs<RecordStockMovement.Result.Recorded>(fixture.record(exit, alice))

        assertEquals(emptyList(), closed())
        assertQuantity("8", fixture.stock.quantityOf(soap.id))
    }

    @Test
    fun `what is found in excess needs a purchase price, the one typed before the last one`() = runTest {
        val soap = item("ART-0001")
        counted(soap, "3")

        assertEquals(CloseInventory.Result.MissingCost(setOf(soap.id)), fixture.closeInventory("", alice))
        assertEquals(CloseInventory.Result.MissingCost(setOf(soap.id)), fixture.closeInventory.problemOf(""))
        assertEquals(0L, fixture.stock.count())
        assertEquals(true, fixture.readInventory(query = "", limit = 100).single().missingCost)

        counted(soap, "3", cost = "750")
        assertNull(fixture.closeInventory.problemOf(""))
        assertEquals(bif("750"), closed().single().content.unitCost)

        // The adjustment is not a purchase: the last purchase price is still unknown.
        assertEquals(emptyMap(), fixture.stock.lastEntryCosts())
        entered(soap, "1", cost = "800")
        counted(soap, "6", cost = "790")
        assertEquals(bif("790"), closed().single().content.unitCost)
    }

    @Test
    fun `an inventory without a count or with too long a note is not closed`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")

        assertEquals(CloseInventory.Result.NothingCounted, fixture.closeInventory("", alice))

        counted(soap, "9")
        val note = "x".repeat(CloseInventory.MAX_NOTE_LENGTH + 1)
        assertEquals(CloseInventory.Result.NoteTooLong, fixture.closeInventory(note, alice))
        assertEquals(1, fixture.inventory.counts().size)
        assertQuantity("10", fixture.stock.quantityOf(soap.id))
    }

    @Test
    fun `who is not a manager closes with the validation of one, and counts with the right to record`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")
        val manager = account(Role.MANAGER)
        val cashier = account(Role.CASHIER)
        counted(soap, "9")
        // Alice acts as a storekeeper would.
        sale.access.denied += Permission.MANAGE_STOCK

        assertFailsWith<PermissionDeniedException> { fixture.closeInventory("", alice) }
        assertFailsWith<PermissionDeniedException> { fixture.closeInventory("", alice, approver = cashier) }
        assertFailsWith<PermissionDeniedException> { fixture.closeInventory("", alice, approver = alice) }
        assertEquals(1, fixture.inventory.counts().size)
        assertQuantity("10", fixture.stock.quantityOf(soap.id))

        val result = fixture.closeInventory("", alice, approver = manager)

        val adjustment = assertIs<CloseInventory.Result.Closed>(result).adjustments.single().content
        assertEquals(alice, adjustment.recordedBy)
        assertEquals(manager, adjustment.approvedBy)
        assertEquals(AuditEvent.InventoryClosed(alice, 1, manager), sale.audit.events.last())
        assertEquals("adjustments=1;approver=${manager.value}", sale.audit.events.last().details)

        sale.access.denied += Permission.RECORD_STOCK
        assertFailsWith<PermissionDeniedException> { counted(soap, "9") }
        assertFailsWith<PermissionDeniedException> { fixture.count.discard(alice) }
        assertFailsWith<PermissionDeniedException> { fixture.setThreshold(soap.id, "1", alice) }
    }

    @Test
    fun `an inventory given up leaves the stock as it was`() = runTest {
        val soap = item("ART-0001")
        entered(soap, "10")
        counted(soap, "2")

        fixture.count.discard(alice)

        assertEquals(emptyList(), fixture.inventory.counts())
        assertQuantity("10", fixture.stock.quantityOf(soap.id))
        assertEquals(CloseInventory.Result.NothingCounted, fixture.closeInventory("", alice))
    }

    @Test
    fun `an item at its threshold or under it is to reorder`() = runTest {
        val soap = item("ART-0001")
        val sugar = item("ART-0002")
        entered(soap, "10")
        entered(sugar, "10")

        assertEquals(SetReorderThreshold.Result.Saved(BigDecimal("10")), fixture.setThreshold(soap.id, "10", alice))
        assertEquals(SetReorderThreshold.Result.Saved(BigDecimal("2.5")), fixture.setThreshold(sugar.id, "2,5", alice))
        assertEquals(AuditEvent.ReorderThresholdSet(alice, sugar.id, "2.5"), sale.audit.events.last())
        assertEquals(SetReorderThreshold.Result.Invalid, fixture.setThreshold(sugar.id, "-1", alice))

        val lines = fixture.read(query = "", limit = 100).associateBy { it.item.id }
        assertEquals(true, lines.getValue(soap.id).toReorder)
        assertEquals(false, lines.getValue(sugar.id).toReorder)
        assertEquals(listOf(soap.id), fixture.read(query = "", limit = 100, toReorderOnly = true).map { it.item.id })
        assertEquals(1, fixture.read.summary().toReorder)

        // Without a threshold nothing is to reorder, even an empty stock.
        assertEquals(SetReorderThreshold.Result.Saved(null), fixture.setThreshold(soap.id, "", alice))
        assertEquals(0, fixture.read.summary().toReorder)
        val service = sale.item("SRV-0001", "5000")
        val refused = SetReorderThreshold.Result.Refused(StockProblem.ITEM_NOT_TRACKED)
        assertEquals(refused, fixture.setThreshold(service.id, "1", alice))
    }

    @Test
    fun `the stock is worth its last purchase prices, and an item without one is left out`() = runTest {
        val soap = item("ART-0001")
        val sugar = item("ART-0002")
        val oil = item("ART-0003")
        entered(soap, "10", cost = "800")
        entered(soap, "5", cost = "812.5", type = MovementType.EI)
        entered(sugar, "4", cost = "650")
        counted(oil, "3", cost = "4000")
        closed()

        val lines = fixture.read(query = "", limit = 100).associateBy { it.item.id }

        assertEquals(bif("812.5"), lines.getValue(soap.id).unitCost)
        assertEquals(bif("12187.5"), lines.getValue(soap.id).value)
        assertEquals(bif("2600"), lines.getValue(sugar.id).value)
        // Found by an inventory, never bought: no purchase price.
        assertNull(lines.getValue(oil.id).value)
        assertEquals(StockSummary(bif("14787.5"), unvalued = 1, toReorder = 0), fixture.read.summary())
        assertEquals(StockSummary.EMPTY, StockFixture().read.summary())
    }

    @Test
    fun `a stock of zero or less is worth nothing`() = runTest {
        val line = StockLine(item("ART-0001"), BigDecimal("-2"), unitCost = bif("800"))

        assertEquals(bif("0"), line.value)
        assertEquals(false, line.toReorder)
        assertEquals(true, line.copy(threshold = BigDecimal.ZERO).toReorder)
    }
}
