package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.stock.Dmc
import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.stock.ImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The customs declarations and the entries of imported goods, on a real database file. */
@RunWith(AndroidJUnit4::class)
class DmcStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name, and short test names: see RoomItemRepositoryTest.
    private val name = "dmc.db"
    private var storage = LocalStorage.unencryptedFile(context, name)
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private var recorded = 0

    private val oil = DmcLine(1, "15119090000", "Huile de palme", BigDecimal("100.5"), "Jerrycans")
    private val rice = DmcLine(2, "10063000000", "Riz", null, "Sacs")

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private fun dmc(reference: String, at: Instant = now, vararg lines: DmcLine = arrayOf(oil, rice)) =
        Dmc(reference, "4000007155", at, lines.toList())

    private fun entry(goods: ImportedGoods?) = StockMovementContent(
        reference = "A1-%06d".format(++recorded),
        itemId = ItemId(1),
        itemCode = "ART-0001",
        designation = "Huile de palme 20 L",
        unit = "bidon",
        type = MovementType.EN,
        quantity = Quantity.of("40"),
        unitCost = Money.of("2500", Currency.BIF),
        description = "",
        occurredAt = now,
        environment = EbmsEnvironment.TEST,
        recordedBy = UserId(1),
        imported = goods,
    )

    @Test
    fun `a declaration is read back exactly`() = runTest {
        val kept = dmc(REFERENCE)
        storage.dmcs.save(kept)

        assertEquals(kept, storage.dmcs.find(REFERENCE))
        assertNull(storage.dmcs.find("2025BIPORC0001"))
    }

    @Test
    fun `saving again replaces the lines`() = runTest {
        storage.dmcs.save(dmc(REFERENCE))
        val again = dmc(REFERENCE, now.plusSeconds(60), oil.copy(quantity = BigDecimal("120")))

        storage.dmcs.save(again)

        assertEquals(again, storage.dmcs.find(REFERENCE))
        assertEquals(listOf(again), storage.dmcs.all())
    }

    @Test
    fun `the last read comes first`() = runTest {
        val first = dmc("2025BIPORC0001", now)
        val second = dmc("2025BIPORC0002", now.plusSeconds(60))
        storage.dmcs.save(first)
        storage.dmcs.save(second)
        assertEquals(listOf(second, first), storage.dmcs.all())

        val firstAgain = first.copy(fetchedAt = now.plusSeconds(120))
        storage.dmcs.save(firstAgain)

        assertEquals(listOf(firstAgain, second), storage.dmcs.all())
    }

    @Test
    fun `an entry keeps its declaration`() = runTest {
        val goods = ImportedGoods(REFERENCE, 1, "15119090000", BigDecimal("20"), "Jerrycans")
        val imported = storage.stock.record(entry(goods))
        val plain = storage.stock.record(entry(goods = null))

        assertEquals(imported, storage.stock.find(imported.id))
        assertEquals(goods, storage.stock.find(imported.id)?.content?.imported)
        assertNull(storage.stock.find(plain.id)?.content?.imported)
    }

    @Test
    fun `the entries of a declaration are found`() = runTest {
        val goods = ImportedGoods(REFERENCE, 1, "15119090000", BigDecimal("20"), "Jerrycans")
        val first = storage.stock.record(entry(goods))
        storage.stock.record(entry(goods.copy(dmcReference = "2025BIPORC0001")))
        storage.stock.record(entry(goods = null))
        val second = storage.stock.record(entry(goods.copy(dmcLine = 2)))

        assertEquals(listOf(first, second), storage.dmcs.entriesOf(REFERENCE))
        assertEquals(emptyList(), storage.dmcs.entriesOf("2025BIPORC9999"))
    }

    private companion object {
        const val REFERENCE = "2025BIPORC7090"
    }
}
