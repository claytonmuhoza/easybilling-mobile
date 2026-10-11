package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The stock movements, their status and their place in the send queue, on a real database file. */
@RunWith(AndroidJUnit4::class)
class StockStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name, and short test names: see RoomItemRepositoryTest.
    private val name = "stock.db"
    private var storage = LocalStorage.unencryptedFile(context, name)
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private var recorded = 0

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private fun content(type: MovementType, quantity: String, item: Long = 1) = StockMovementContent(
        reference = "A1-%06d".format(++recorded),
        itemId = ItemId(item),
        itemCode = "ART-%04d".format(item),
        designation = "Savon de Marseille",
        unit = "pièce",
        type = type,
        quantity = Quantity.of(quantity),
        unitCost = Money.of("812.5", Currency.BIF),
        description = "Facture fournisseur F-88",
        invoiceRef = "FN-00001/2026".takeIf { type == MovementType.ER },
        occurredAt = now,
        environment = EbmsEnvironment.TEST,
        recordedBy = UserId(1),
        approvedBy = UserId(2).takeIf { type == MovementType.SP },
    )

    private suspend fun recorded(type: MovementType, quantity: String, item: Long = 1): StockMovement =
        storage.stock.record(content(type, quantity, item))

    @Test
    fun `a movement is read back exactly, pending`() = runTest {
        val entry = recorded(MovementType.ER, "12.5")
        val exit = recorded(MovementType.SP, "2")

        assertEquals(entry, storage.stock.find(entry.id))
        assertEquals(exit, storage.stock.find(exit.id))
        assertEquals("FN-00001/2026", storage.stock.find(entry.id)?.content?.invoiceRef)
        assertEquals(UserId(2), storage.stock.find(exit.id)?.content?.approvedBy)
        assertEquals(MovementStatus.Pending, storage.stock.statusOf(entry.id))
        assertEquals(2L, storage.stock.count())
        assertNull(storage.stock.find(MovementId(99)))
        assertNull(storage.stock.statusOf(MovementId(99)))
    }

    @Test
    fun `the stock is the exact sum of the movements`() = runTest {
        recorded(MovementType.EI, "10")
        recorded(MovementType.EN, "0.1")
        recorded(MovementType.EN, "0.2")
        recorded(MovementType.SC, "4.05")
        recorded(MovementType.EN, "7", item = 2)

        // 10 + 0.1 + 0.2 - 4.05, without the error of a floating sum.
        assertEquals(0, BigDecimal("6.25").compareTo(storage.stock.quantityOf(ItemId(1))))
        assertEquals(0, BigDecimal("7").compareTo(storage.stock.quantityOf(ItemId(2))))
        assertEquals(0, storage.stock.quantityOf(ItemId(3)).signum())
        val quantities = storage.stock.quantities().mapValues { it.value.stripTrailingZeros().toPlainString() }
        assertEquals(mapOf(ItemId(1) to "6.25", ItemId(2) to "7"), quantities)
    }

    @Test
    fun `the movements written with an invoice are found, and a voided one no longer counts`() = runTest {
        val opening = recorded(MovementType.EI, "10")
        val invoice = InvoiceId(7)
        val exit = storage.stock.record(content(MovementType.SN, "4").copy(invoiceId = invoice))
        val other = storage.stock.record(content(MovementType.SN, "1").copy(invoiceId = InvoiceId(8)))

        assertEquals(listOf(exit), storage.stock.ofInvoice(invoice))
        assertEquals(invoice, storage.stock.find(exit.id)?.content?.invoiceId)
        assertNull(storage.stock.find(opening.id)?.content?.invoiceId)
        assertEquals(emptyList(), storage.stock.ofInvoice(InvoiceId(99)))
        assertEquals(0, BigDecimal("5").compareTo(storage.stock.quantityOf(ItemId(1))))

        storage.stock.setStatus(exit.id, MovementStatus.Voided, now)

        assertEquals(MovementStatus.Voided, storage.stock.statusOf(exit.id))
        assertEquals(0, BigDecimal("9").compareTo(storage.stock.quantityOf(ItemId(1))))
        assertEquals(0, BigDecimal("9").compareTo(storage.stock.quantities().getValue(ItemId(1))))
        assertEquals(emptyList(), storage.stock.toResolve())
        assertEquals(listOf(other, exit, opening), storage.stock.movementsOf(ItemId(1), limit = 10))

        // Its invoice is sent again: the movement counts again.
        storage.stock.setStatus(exit.id, MovementStatus.Pending, now)
        assertEquals(0, BigDecimal("5").compareTo(storage.stock.quantityOf(ItemId(1))))
    }

    @Test
    fun `the movements of an item are listed the most recent first`() = runTest {
        val first = recorded(MovementType.EI, "10")
        recorded(MovementType.EN, "7", item = 2)
        val second = recorded(MovementType.SC, "1")
        val third = recorded(MovementType.EN, "3")

        assertEquals(listOf(third, second, first), storage.stock.movementsOf(ItemId(1), limit = 10))
        assertEquals(listOf(third, second), storage.stock.movementsOf(ItemId(1), limit = 2))
    }

    @Test
    fun `only the status of a movement changes, along the allowed transitions`() = runTest {
        val movement = recorded(MovementType.EN, "5")
        val refusal = MovementStatus.Rejected("Identifiant système incorrect.")

        storage.stock.setStatus(movement.id, refusal, now)
        assertEquals(refusal, storage.stock.statusOf(movement.id))
        assertFailsWith<IllegalStateException> { storage.stock.setStatus(movement.id, MovementStatus.Declared, now) }
        storage.stock.setStatus(movement.id, MovementStatus.Pending, now)
        storage.stock.setStatus(movement.id, MovementStatus.ToConfirm, now)
        storage.stock.setStatus(movement.id, MovementStatus.Declared, now)

        assertEquals(MovementStatus.Declared, storage.stock.statusOf(movement.id))
        assertFailsWith<IllegalStateException> { storage.stock.setStatus(movement.id, MovementStatus.Pending, now) }
        assertEquals(movement, storage.stock.find(movement.id))
    }

    @Test
    fun `the movements a manager decides are the refused and the uncertain ones`() = runTest {
        val refused = recorded(MovementType.EN, "5")
        recorded(MovementType.EN, "5")
        val uncertain = recorded(MovementType.EN, "5")
        val declared = recorded(MovementType.EN, "5")
        storage.stock.setStatus(refused.id, MovementStatus.Rejected("Refus"), now)
        storage.stock.setStatus(uncertain.id, MovementStatus.ToConfirm, now)
        storage.stock.setStatus(declared.id, MovementStatus.Declared, now)

        assertEquals(listOf(refused, uncertain), storage.stock.toResolve())
    }

    @Test
    fun `the database itself refuses any change to a movement`() = runTest {
        val movement = recorded(MovementType.EN, "5")
        val database = storage.database.openHelper.writableDatabase

        for (statement in listOf("UPDATE stock_movements SET quantity = '50'", "DELETE FROM stock_movements")) {
            assertFailsWith<Exception>(statement) { database.execSQL(statement) }
        }
        assertEquals(movement, storage.stock.find(movement.id))
    }

    @Test
    fun `the database itself refuses two movements with the same reference`() = runTest {
        val movement = recorded(MovementType.EN, "5")

        assertFailsWith<SQLiteConstraintException> { storage.stock.record(movement.content) }
        assertEquals(1L, storage.stock.count())
    }

    @Test
    fun `a movement keeps its place in the send queue, among the invoices`() = runTest {
        val movement = recorded(MovementType.EN, "5")
        storage.outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), now)

        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)

        assertEquals(
            listOf<OutboxOperation>(OutboxOperation.SendStockMovement(movement.id)),
            storage.outbox.pending().map { it.operation },
        )
        assertNull(storage.outbox.pending().single().operation.invoice)
        assertEquals(movement, storage.stock.find(movement.id))
    }
}
