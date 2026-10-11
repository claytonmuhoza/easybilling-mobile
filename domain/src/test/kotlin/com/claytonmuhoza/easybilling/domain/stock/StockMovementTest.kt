package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
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
 * The stock movements recorded by hand (UC-F01, F02, F05, F07, F10, D53): what is typed, the
 * unique reference, the stock as the sum of the movements, who may, and what a manager decides of
 * a refused or uncertain movement.
 */
class StockMovementTest {

    private val fixture = StockFixture()
    private val sale = fixture.sale
    private val alice = sale.alice

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private suspend fun account(vararg roles: Role): UserId {
        val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
        return sale.users.create(NewUser("User ${sale.users.count() + 1}", roles.toSet(), secret)).id
    }

    private suspend fun soap(): Item {
        if (sale.profiles.current() == null) sale.ready()
        return sale.items.findByCode("ART-0001") ?: sale.item("ART-0001", "1000", stockTracked = true)
    }

    private suspend fun record(
        type: MovementType?,
        quantity: String,
        unitCost: String = "800",
        description: String = "",
        item: ItemId? = null,
        actor: UserId = alice,
        approver: UserId? = null,
    ): RecordStockMovement.Result =
        fixture.record(StockMovementForm(item ?: soap().id, type, quantity, unitCost, description), actor, approver)

    private suspend fun recorded(type: MovementType, quantity: String, description: String = ""): StockMovement =
        assertIs<RecordStockMovement.Result.Recorded>(record(type, quantity, description = description)).movement

    private fun invalid(vararg problems: MovementProblem) = RecordStockMovement.Result.Invalid(problems.toSet())

    @Test
    fun `an entry copies the item, takes the next reference and is queued for the OBR`() = runTest {
        val item = soap()

        val movement = recorded(MovementType.EN, "12,5", description = " Facture fournisseur F-88 ")

        val content = movement.content
        assertEquals("000001", content.reference)
        assertEquals(item.id, content.itemId)
        assertEquals("ART-0001", content.itemCode)
        assertEquals("Article ART-0001", content.designation)
        assertEquals("pièce", content.unit)
        assertEquals(MovementType.EN, content.type)
        assertEquals(BigDecimal("12.5"), content.quantity.value)
        assertEquals(bif("800"), content.unitCost)
        assertEquals("Facture fournisseur F-88", content.description)
        assertEquals("000001 Facture fournisseur F-88", content.obrDescription)
        assertEquals(sale.time.now(), content.occurredAt)
        assertEquals(EbmsEnvironment.TEST, content.environment)
        assertEquals(alice, content.recordedBy)
        assertNull(content.invoiceRef)
        assertEquals(MovementStatus.Pending, fixture.stock.statusOf(movement.id))
        assertEquals(OutboxOperation.SendStockMovement(movement.id), sale.outbox.pending().last().operation)
        assertEquals(AuditEvent.StockMovementRecorded(alice, movement.id, "000001", null), sale.audit.events.last())
        assertEquals("movement=${movement.id.value};reference=000001", sale.audit.events.last().details)
    }

    @Test
    fun `the references follow each other, with the code of the device when several devices bill`() = runTest {
        soap()
        assertEquals("000001", recorded(MovementType.EI, "10").content.reference)
        assertEquals("000002", recorded(MovementType.EN, "5").content.reference)

        val numbering = checkNotNull(sale.numbering.settings())
        sale.numbering.save(numbering.copy(multipleDevices = true, deviceCode = "A1"))

        assertEquals("A1-000003", recorded(MovementType.EN, "5").content.reference)
        assertEquals("A1-000042", MovementReference.of("A1", 42))
        assertEquals("1234567", MovementReference.of("", 1_234_567))
    }

    @Test
    fun `the stock of an item is the sum of its movements`() = runTest {
        val item = soap()
        val other = sale.item("ART-0002", "500", stockTracked = true)
        assertEquals(BigDecimal.ZERO, fixture.stock.quantityOf(item.id))

        recorded(MovementType.EI, "10")
        recorded(MovementType.EN, "2.5")
        recorded(MovementType.ST, "4", description = "Vers le dépôt")
        record(MovementType.EN, "7", item = other.id)

        assertEquals(0, BigDecimal("8.5").compareTo(fixture.stock.quantityOf(item.id)))
        assertEquals(0, BigDecimal("7").compareTo(fixture.stock.quantityOf(other.id)))
        val lines = fixture.read(query = "", limit = 100)
        assertEquals(listOf("ART-0001", "ART-0002"), lines.map { it.item.code })
        assertEquals(listOf("8.5", "7"), lines.map { it.quantity.stripTrailingZeros().toPlainString() })
        assertEquals(
            listOf(MovementType.ST, MovementType.EN, MovementType.EI),
            fixture.stock.movementsOf(item.id, limit = 10).map { it.content.type },
        )
    }

    @Test
    fun `only the items kept in stock are listed, and searched as the catalogue is`() = runTest {
        soap()
        sale.items.create(
            "SRV-0001",
            sale.items.findByCode("ART-0001")!!.details.copy(designation = "Livraison", stockTracked = false),
        )
        sale.item("ART-0002", "500", stockTracked = true)

        assertEquals(listOf("ART-0001", "ART-0002"), fixture.read("", 100).map { it.item.code })
        assertEquals(listOf("ART-0002"), fixture.read("0002", 100).map { it.item.code })
        assertEquals(BigDecimal.ZERO, fixture.read("0002", 100).single().quantity)
    }

    @Test
    fun `an exit never takes more than the stock`() = runTest {
        soap()
        recorded(MovementType.EN, "3")

        val result = record(MovementType.SC, "3.01", description = "Carton tombé")

        val available = assertIs<RecordStockMovement.Result.InsufficientStock>(result).available
        assertEquals(0, BigDecimal("3").compareTo(available))
        assertEquals(1L, fixture.stock.count())
        assertIs<RecordStockMovement.Result.Recorded>(record(MovementType.SC, "3", description = "Carton tombé"))
        assertEquals(0, fixture.stock.quantityOf(soap().id).signum())
    }

    @Test
    fun `an exit declares the unit sale price with taxes, an entry the purchase price typed`() = runTest {
        soap()
        val bread = sale.item("ART-0003", "1000", PriceMode.INCLUDING_TAXES, stockTracked = true)
        recorded(MovementType.EN, "5")
        record(MovementType.EN, "5", unitCost = "650.5", item = bread.id)

        // 1 000 without taxes, VAT 18 %.
        assertEquals(bif("1180"), recorded(MovementType.SP, "1", description = "Perdu").content.unitCost)
        val exit = record(MovementType.SP, "1", unitCost = "1", description = "Perdu", item = bread.id)
        assertEquals(bif("1000"), assertIs<RecordStockMovement.Result.Recorded>(exit).movement.content.unitCost)
        assertEquals(bif("650.5"), fixture.stock.movementsOf(bread.id, 10).last().content.unitCost)
    }

    @Test
    fun `the type, the quantity, the purchase price and the description are checked`() = runTest {
        soap()
        recorded(MovementType.EN, "5")

        assertEquals(invalid(MovementProblem.TYPE_MISSING), record(null, "1"))
        // The types that come with a sale, a credit note or an inventory are not typed.
        for (type in listOf(MovementType.SN, MovementType.ER, MovementType.EAJ, MovementType.SAJ)) {
            assertIs<RecordStockMovement.Result.Invalid>(record(type, "1", description = "x"))
        }
        assertEquals(invalid(MovementProblem.QUANTITY_INVALID), record(MovementType.EN, "0"))
        assertEquals(invalid(MovementProblem.QUANTITY_INVALID), record(MovementType.EN, "1.234"))
        assertEquals(invalid(MovementProblem.QUANTITY_INVALID), record(MovementType.EN, "abc"))
        assertEquals(invalid(MovementProblem.COST_INVALID), record(MovementType.EN, "1", unitCost = "0"))
        assertEquals(invalid(MovementProblem.COST_INVALID), record(MovementType.EI, "1", unitCost = "x"))
        assertEquals(invalid(MovementProblem.DESCRIPTION_REQUIRED), record(MovementType.SV, "1", description = "  "))
        // The purchase price is not asked for an exit.
        val exit = record(MovementType.SV, "1", unitCost = "", description = "Vol")
        assertIs<RecordStockMovement.Result.Recorded>(exit)
        assertEquals(2L, fixture.stock.count())
    }

    @Test
    fun `the description holds with its reference in the 500 characters of the OBR`() = runTest {
        soap()
        val longest = StockMovementContent.maxTypedLength("000001")
        assertEquals(493, longest)

        assertEquals(
            invalid(MovementProblem.DESCRIPTION_TOO_LONG),
            record(MovementType.EN, "1", description = "x".repeat(longest + 1)),
        )
        val movement = recorded(MovementType.EN, "1", description = "x".repeat(longest))
        assertEquals(StockMovementContent.MAX_DESCRIPTION_LENGTH, movement.content.obrDescription.length)
    }

    @Test
    fun `an item that is not kept in stock, archived or unknown does not move`() = runTest {
        val item = soap()
        val service = sale.items.create("SRV-0001", item.details.copy(stockTracked = false))
        val archived = sale.item("ART-0009", "500", stockTracked = true)
        sale.items.setArchived(archived.id, true)

        fun refused(problem: StockProblem) = RecordStockMovement.Result.Refused(problem)
        assertEquals(refused(StockProblem.ITEM_NOT_TRACKED), record(MovementType.EN, "1", item = service.id))
        assertEquals(refused(StockProblem.ITEM_ARCHIVED), record(MovementType.EN, "1", item = archived.id))
        assertEquals(refused(StockProblem.ITEM_NOT_FOUND), record(MovementType.EN, "1", item = ItemId(99)))
        assertEquals(0L, fixture.stock.count())
    }

    @Test
    fun `a storekeeper records entries alone, and an exit that is not a sale with the approval of a manager`() =
        runTest {
            soap()
            val storekeeper = account(Role.STOREKEEPER)
            val other = account(Role.STOREKEEPER)
            val manager = account(Role.MANAGER)
            // Nobody holds the right in person: only an approval lets the exit through.
            sale.access.denied += Permission.MANAGE_STOCK

            assertIs<RecordStockMovement.Result.Recorded>(record(MovementType.EN, "5", actor = storekeeper))
            // A transfer is an exit a storekeeper records alone (UC-F07 names the others).
            assertIs<RecordStockMovement.Result.Recorded>(
                record(MovementType.ST, "1", description = "Dépôt", actor = storekeeper),
            )
            assertFailsWith<PermissionDeniedException> {
                record(MovementType.SP, "1", description = "Perdu", actor = storekeeper)
            }
            assertFailsWith<PermissionDeniedException> {
                record(MovementType.SP, "1", description = "Perdu", actor = storekeeper, approver = other)
            }
            assertFailsWith<PermissionDeniedException> {
                record(MovementType.SP, "1", description = "Perdu", actor = manager, approver = manager)
            }

            val result = record(MovementType.SP, "1", description = "Perdu", actor = storekeeper, approver = manager)

            val movement = assertIs<RecordStockMovement.Result.Recorded>(result).movement
            assertEquals(manager, movement.content.approvedBy)
            val event = sale.audit.events.last()
            assertEquals(AuditEvent.StockMovementRecorded(storekeeper, movement.id, "000003", manager), event)
            assertEquals("movement=3;reference=000003;approver=${manager.value}", event.details)
        }

    @Test
    fun `a user who may not record a movement records none`() = runTest {
        soap()
        sale.access.denied += Permission.RECORD_STOCK

        assertFailsWith<PermissionDeniedException> { record(MovementType.EN, "5") }
        assertEquals(0L, fixture.stock.count())
    }

    @Test
    fun `a manager sends a refused movement again, and it still counts in the stock meanwhile`() = runTest {
        val item = soap()
        val movement = recorded(MovementType.EN, "5")
        sale.outbox.pending().forEach { sale.outbox.remove(it.id) }
        val refusal = MovementStatus.Rejected("Identifiant système incorrect.")
        fixture.stock.setStatus(movement.id, refusal, sale.time.now())

        assertEquals(0, BigDecimal("5").compareTo(fixture.stock.quantityOf(item.id)))
        assertEquals(listOf(movement), fixture.stock.toResolve())
        assertEquals(ResolveStockMovement.Result.NOT_APPLICABLE, fixture.resolve.holdAsDeclared(movement.id, alice))

        assertEquals(ResolveStockMovement.Result.DONE, fixture.resolve.resend(movement.id, alice))

        assertEquals(MovementStatus.Pending, fixture.stock.statusOf(movement.id))
        assertEquals(listOf(OutboxOperation.SendStockMovement(movement.id)), sale.outbox.pending().map { it.operation })
        assertEquals(AuditEvent.StockMovementResolved(alice, movement.id, resent = true), sale.audit.events.last())
        assertEquals("STOCK_MOVEMENT_RESENT", sale.audit.events.last().action)
        assertEquals(emptyList(), fixture.stock.toResolve())
    }

    @Test
    fun `a manager holds an uncertain movement as declared, or sends it again`() = runTest {
        soap()
        val held = recorded(MovementType.EN, "5")
        val resent = recorded(MovementType.EN, "2")
        sale.outbox.pending().forEach { sale.outbox.remove(it.id) }
        fixture.stock.setStatus(held.id, MovementStatus.ToConfirm, sale.time.now())
        fixture.stock.setStatus(resent.id, MovementStatus.ToConfirm, sale.time.now())

        assertEquals(ResolveStockMovement.Result.DONE, fixture.resolve.holdAsDeclared(held.id, alice))
        assertEquals(ResolveStockMovement.Result.DONE, fixture.resolve.resend(resent.id, alice))

        assertEquals(MovementStatus.Declared, fixture.stock.statusOf(held.id))
        assertEquals(MovementStatus.Pending, fixture.stock.statusOf(resent.id))
        assertEquals(listOf(OutboxOperation.SendStockMovement(resent.id)), sale.outbox.pending().map { it.operation })
        val actions = sale.audit.events.takeLast(2).map { it.action }
        assertEquals(listOf("STOCK_MOVEMENT_HELD_AS_DECLARED", "STOCK_MOVEMENT_RESENT"), actions)
        // A declared movement is decided no more.
        assertEquals(ResolveStockMovement.Result.NOT_APPLICABLE, fixture.resolve.resend(held.id, alice))
        assertEquals(ResolveStockMovement.Result.NOT_APPLICABLE, fixture.resolve.resend(MovementId(99), alice))
    }

    @Test
    fun `only a user who manages the stock decides of a movement`() = runTest {
        soap()
        val movement = recorded(MovementType.EN, "5")
        fixture.stock.setStatus(movement.id, MovementStatus.ToConfirm, sale.time.now())
        sale.access.denied += Permission.MANAGE_STOCK

        assertFailsWith<PermissionDeniedException> { fixture.resolve.resend(movement.id, alice) }
        assertFailsWith<PermissionDeniedException> { fixture.resolve.holdAsDeclared(movement.id, alice) }
        assertEquals(MovementStatus.ToConfirm, fixture.stock.statusOf(movement.id))
    }

    @Test
    fun `the status of a movement only moves along the allowed transitions`() {
        val rejected = MovementStatus.Rejected("refus")
        assertEquals(true, MovementStatus.Pending.canBecome(MovementStatus.Declared))
        assertEquals(true, MovementStatus.Pending.canBecome(rejected))
        assertEquals(true, MovementStatus.Pending.canBecome(MovementStatus.ToConfirm))
        assertEquals(true, rejected.canBecome(MovementStatus.Pending))
        assertEquals(false, rejected.canBecome(MovementStatus.Declared))
        assertEquals(true, MovementStatus.ToConfirm.canBecome(MovementStatus.Declared))
        assertEquals(false, MovementStatus.Declared.canBecome(MovementStatus.Pending))
        val statuses = listOf(MovementStatus.Pending, MovementStatus.Declared, rejected, MovementStatus.ToConfirm)
        assertEquals(listOf(false, false, true, true), statuses.map { it.toResolve })
    }

    @Test
    fun `the fourteen types of the OBR are known, each an entry or an exit`() {
        assertEquals(
            listOf("EN", "ER", "EI", "EAJ", "ET", "EAU", "SN", "SP", "SV", "SD", "SC", "SAJ", "ST", "SAU"),
            MovementType.entries.map { it.code },
        )
        assertEquals(
            setOf("EN", "ER", "EI", "EAJ", "ET", "EAU"),
            MovementType.entries.filter { it.direction == MovementDirection.ENTRY }.map { it.code }.toSet(),
        )
        assertEquals(true, MovementType.RECORDED_BY_HAND.containsAll(MovementType.VALIDATED_BY_MANAGER))
    }

    @Test
    fun `what would stop a movement is told before anything is written`() = runTest {
        val item = soap()
        recorded(MovementType.EI, "3")
        val written = fixture.stock.count()
        val queued = sale.outbox.pending().size

        fun exit(quantity: String, description: String = "Carton mouillé") =
            StockMovementForm(item.id, MovementType.SP, quantity, description = description)

        assertNull(fixture.record.problemOf(exit("2")))
        val undescribed = fixture.record.problemOf(exit("2", description = ""))
        assertEquals(invalid(MovementProblem.DESCRIPTION_REQUIRED), undescribed)
        val short = assertIs<RecordStockMovement.Result.InsufficientStock>(fixture.record.problemOf(exit("4")))
        assertEquals(0, BigDecimal("3").compareTo(short.available))
        assertEquals(written, fixture.stock.count())
        assertEquals(queued, sale.outbox.pending().size)
    }
}
