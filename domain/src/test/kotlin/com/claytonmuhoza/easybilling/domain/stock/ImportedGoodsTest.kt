package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.testing.FakeObrGateway
import com.claytonmuhoza.easybilling.domain.testing.StockFixture
import com.claytonmuhoza.easybilling.domain.transmission.DmcOutcome
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The imported goods (UC-F03, F04): a customs declaration is read from the OBR and kept, and what is
 * entered of each of its lines is an entry EN that never goes beyond the quantity declared.
 */
class ImportedGoodsTest {

    private val fixture = StockFixture()
    private val sale = fixture.sale
    private val alice = sale.alice
    private val gateway = FakeObrGateway()
    private val fetch = fixture.fetchDmc(gateway)

    private val oil = DmcLine(1, "1511.90.00", "Huile de palme", BigDecimal("100"), "Jerrycans")
    private val rice = DmcLine(2, "1006.30.00", "Riz", null, "Sacs")

    /** An item kept in stock. */
    private suspend fun item(code: String = "ART-0001"): Item {
        if (sale.profiles.current() == null) sale.ready()
        return sale.items.findByCode(code) ?: sale.item(code, "1000", stockTracked = true)
    }

    /** Reads the declaration [REFERENCE], which holds [lines]. */
    private suspend fun fetched(vararg lines: DmcLine = arrayOf(oil, rice)): Dmc {
        item()
        gateway.dmcs[REFERENCE] = lines.toList()
        return assertIs<FetchDmc.Result.Fetched>(fetch(REFERENCE, alice)).dmc
    }

    private fun form(item: Item?, quantity: String = "40", line: Int = 1) = ImportForm(
        dmcReference = REFERENCE,
        dmcLine = line,
        itemId = item?.id,
        quantity = quantity,
        unitCost = "2500",
        piecesPerPacket = "20",
        packaging = "Jerrycans",
    )

    private suspend fun entered(item: Item, quantity: String = "40", line: Int = 1): StockMovement =
        assertIs<EnterImportedGoods.Result.Recorded>(fixture.enterImported(form(item, quantity, line), alice)).movement

    private fun assertQuantity(expected: String, actual: BigDecimal?) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Quantity: $actual")

    @Test
    fun `a declaration is read for the NIF of the business and kept with its lines`() = runTest {
        val dmc = fetched()

        val tin = sale.profiles.current()!!.profile.tin
        assertEquals(Triple("getDmcItems", tin, REFERENCE), gateway.calls.single())
        assertEquals(Dmc(REFERENCE, tin, sale.time.now(), listOf(oil, rice)), dmc)
        assertEquals(dmc, fixture.dmcs.find(REFERENCE))
        assertEquals(listOf(dmc), fixture.dmcs.all())
    }

    @Test
    fun `the reference is trimmed, and an empty or too long one is not sent`() = runTest {
        item()
        gateway.dmcs[REFERENCE] = listOf(oil)

        assertIs<FetchDmc.Result.Fetched>(fetch("  $REFERENCE ", alice))
        assertEquals(FetchDmc.Result.InvalidReference, fetch("  ", alice))
        assertEquals(FetchDmc.Result.InvalidReference, fetch("X".repeat(ImportedGoods.MAX_LENGTH + 1), alice))
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun `a refusal, an empty declaration and a lost answer keep nothing`() = runTest {
        item()

        assertEquals(FetchDmc.Result.Refused("La reference DMC n'appartient pas a ce NIF"), fetch(REFERENCE, alice))
        gateway.dmcs[REFERENCE] = emptyList()
        assertEquals(FetchDmc.Result.Empty, fetch(REFERENCE, alice))
        gateway.forcedDmcs += DmcOutcome.Unavailable("TIMEOUT")
        assertEquals(FetchDmc.Result.Unavailable("TIMEOUT"), fetch(REFERENCE, alice))

        assertEquals(emptyList(), fixture.dmcs.all())
    }

    @Test
    fun `a refused token is renewed once`() = runTest {
        item()
        gateway.dmcs[REFERENCE] = listOf(oil)
        gateway.forcedDmcs += DmcOutcome.TokenRefused

        assertIs<FetchDmc.Result.Fetched>(fetch(REFERENCE, alice))
        assertEquals(2, gateway.calls.size)

        gateway.forcedDmcs += listOf(DmcOutcome.TokenRefused, DmcOutcome.TokenRefused)
        assertEquals(FetchDmc.Result.Unavailable("TOKEN_REFUSED"), fetch(REFERENCE, alice))
    }

    @Test
    fun `nothing is read while the business is not configured`() = runTest {
        assertEquals(FetchDmc.Result.NotConfigured, fetch(REFERENCE, alice))
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `reading a declaration again replaces it and keeps what was entered`() = runTest {
        fetched()
        val soap = item()
        entered(soap, "40")
        gateway.dmcs[REFERENCE] = listOf(oil.copy(quantity = BigDecimal("120")))

        assertIs<FetchDmc.Result.Fetched>(fetch(REFERENCE, alice))

        val line = fixture.readDmc(REFERENCE)!!.lines.single()
        assertQuantity("40", line.entered)
        assertQuantity("80", line.remaining)
    }

    @Test
    fun `an entry is a movement EN with the declaration, queued and audited`() = runTest {
        fetched()
        val soap = item()

        val movement = entered(soap, "40,5")

        val content = movement.content
        assertEquals(MovementType.EN, content.type)
        assertQuantity("40.5", content.quantity.value)
        assertEquals(Money.of("2500", Currency.BIF), content.unitCost)
        assertEquals(ImportedGoods(REFERENCE, 1, "1511.90.00", BigDecimal("20"), "Jerrycans"), content.imported)
        assertEquals(MovementStatus.Pending, fixture.stock.statusOf(movement.id))
        assertQuantity("40.5", fixture.stock.quantityOf(soap.id))
        assertEquals(listOf(OutboxOperation.SendStockMovement(movement.id)), sale.outbox.pending().map { it.operation })
        assertEquals(
            AuditEvent.StockMovementRecorded(alice, movement.id, content.reference, approver = null),
            sale.audit.events.last(),
        )
    }

    @Test
    fun `a declaration is entered in several times, never beyond its quantity`() = runTest {
        fetched()
        val soap = item()
        entered(soap, "40")
        entered(soap, "35")

        val beyond = fixture.enterImported(form(soap, "25.01"), alice)

        assertQuantity("25", assertIs<EnterImportedGoods.Result.ExceedsDmc>(beyond).remaining)
        entered(soap, "25")
        val line = fixture.readDmc(REFERENCE)!!.lines.first()
        assertQuantity("100", line.entered)
        assertQuantity("0", line.remaining)
        assertEquals(soap, line.item)
        assertQuantity("100", fixture.stock.quantityOf(soap.id))
    }

    @Test
    fun `a line the OBR gave no quantity for has no limit`() = runTest {
        fetched()
        val soap = item()

        entered(soap, "5000", line = 2)

        val progress = fixture.readDmc(REFERENCE)!!.lines
        assertNull(progress[1].remaining)
        assertQuantity("5000", progress[1].entered)
        assertQuantity("0", progress[0].entered)
        assertNull(progress[0].item)
    }

    @Test
    fun `what is typed wrong is refused with every problem and records nothing`() = runTest {
        fetched()
        val soap = item()
        val wrong = ImportForm(
            dmcReference = REFERENCE,
            dmcLine = 1,
            itemId = soap.id,
            quantity = "0",
            unitCost = "abc",
            piecesPerPacket = "1.234",
            packaging = " ",
            description = "x".repeat(StockMovementContent.MAX_DESCRIPTION_LENGTH),
        )

        val expected = setOf(
            ImportProblem.QUANTITY_INVALID,
            ImportProblem.COST_INVALID,
            ImportProblem.PER_PACKET_INVALID,
            ImportProblem.PACKAGING_MISSING,
            ImportProblem.DESCRIPTION_TOO_LONG,
        )
        assertEquals(EnterImportedGoods.Result.Invalid(expected), fixture.enterImported(wrong, alice))
        assertEquals(
            EnterImportedGoods.Result.Invalid(setOf(ImportProblem.PACKAGING_TOO_LONG)),
            fixture.enterImported(form(soap).copy(packaging = "x".repeat(ImportedGoods.MAX_LENGTH + 1)), alice),
        )
        assertEquals(
            EnterImportedGoods.Result.Invalid(setOf(ImportProblem.ITEM_MISSING)),
            fixture.enterImported(form(item = null), alice),
        )
        assertEquals(0, fixture.stock.count())
        assertEquals(emptyList(), sale.outbox.pending())
    }

    @Test
    fun `an unknown declaration or line, and an item that cannot move, are refused`() = runTest {
        fetched()
        val soap = item()
        val service = sale.item("SRV-0001", "5000")
        val archived = item("ART-0009")
        sale.items.setArchived(archived.id, true)

        assertEquals(EnterImportedGoods.Result.LineNotFound, fixture.enterImported(form(soap, line = 3), alice))
        assertEquals(
            EnterImportedGoods.Result.LineNotFound,
            fixture.enterImported(form(soap).copy(dmcReference = "2025BIPORC0001"), alice),
        )
        assertEquals(
            EnterImportedGoods.Result.Refused(StockProblem.ITEM_NOT_TRACKED),
            fixture.enterImported(form(service), alice),
        )
        assertEquals(
            EnterImportedGoods.Result.Refused(StockProblem.ITEM_ARCHIVED),
            fixture.enterImported(form(archived), alice),
        )
        assertEquals(0, fixture.stock.count())
    }

    @Test
    fun `reading and entering need the right to record a stock movement`() = runTest {
        fetched()
        val soap = item()
        sale.access.denied += Permission.RECORD_STOCK

        assertFailsWith<PermissionDeniedException> { fetch(REFERENCE, alice) }
        assertFailsWith<PermissionDeniedException> { fixture.enterImported(form(soap), alice) }
        assertEquals(0, fixture.stock.count())
    }

    @Test
    fun `a declaration never read has no progress`() = runTest {
        assertNull(fixture.readDmc(REFERENCE))
    }

    private companion object {
        /** The reference of the example of the specification (page 33). */
        const val REFERENCE = "2025BIPORC7090"
    }
}
