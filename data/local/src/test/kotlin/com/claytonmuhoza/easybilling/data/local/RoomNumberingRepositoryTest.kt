package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The numbering and its counters, on a real database: no gap and no duplicate, whatever happens. */
@RunWith(AndroidJUnit4::class)
class RoomNumberingRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "numbering-test.db"
    private val storage = LocalStorage.unencryptedFile(context, name)
    private val numbering = storage.numbering
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val allocate = AllocateInvoiceNumber(numbering, { now }, storage.transactions)

    private val settings = NumberingSettings(
        template = "{DEVICE}-{TYPE}-{NNNNN}/{YYYY}",
        reset = CounterReset.YEARLY,
        multipleDevices = true,
        deviceCode = "A1",
    )

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private suspend fun next(type: DocumentType = DocumentType.FN) =
        assertIs<AllocateInvoiceNumber.Result.Allocated>(allocate(type, now)).number

    @Test
    fun `a new database has no numbering and no counter`() = runTest {
        assertNull(numbering.settings())
        assertEquals(0, numbering.lastCounter(DocumentType.FN, 2026))
    }

    @Test
    fun `the numbering is read back with every field and replaced by the next save`() = runTest {
        numbering.save(settings)
        assertEquals(settings, numbering.settings())

        val lockedAt = Instant.parse("2026-10-03T08:00:00.123Z")
        val locked = NumberingSettings("{TYPE}{NNN}", CounterReset.NEVER, multipleDevices = false, "", lockedAt)
        numbering.save(locked)
        assertEquals(locked, numbering.settings())
    }

    @Test
    fun `each type of document and each period has its own counter`() = runTest {
        numbering.setLastCounter(DocumentType.FN, 2026, 41)
        numbering.setLastCounter(DocumentType.FN, 2026, 42)
        numbering.setLastCounter(DocumentType.FA, 2026, 3)
        numbering.setLastCounter(DocumentType.FN, 2027, 9_999_999_999)

        assertEquals(42, numbering.lastCounter(DocumentType.FN, 2026))
        assertEquals(3, numbering.lastCounter(DocumentType.FA, 2026))
        assertEquals(9_999_999_999, numbering.lastCounter(DocumentType.FN, 2027))
        assertEquals(0, numbering.lastCounter(DocumentType.RC, 2026))
    }

    @Test
    fun `numbers are given from the stored numbering and lock it`() = runTest {
        numbering.save(settings)

        assertEquals("A1-FN-00001/2026", next())
        assertEquals("A1-FN-00002/2026", next())
        assertEquals("A1-RHF-00001/2026", next(DocumentType.RHF))
        assertEquals(now, numbering.settings()?.lockedAt)
    }

    @Test
    fun `a number taken by a transaction that fails is given again`() = runTest {
        numbering.save(settings)
        assertEquals("A1-FN-00001/2026", next())

        assertFailsWith<IllegalStateException> {
            storage.transactions.run {
                assertEquals("A1-FN-00002/2026", next())
                error("Simulated crash before the invoice is stored")
            }
        }

        assertEquals(1, numbering.lastCounter(DocumentType.FN, 2026))
        assertEquals("A1-FN-00002/2026", next())
    }

    @Test
    fun `the first number of a transaction that fails does not lock the numbering`() = runTest {
        numbering.save(settings)

        assertFailsWith<IllegalStateException> {
            storage.transactions.run {
                next()
                error("Simulated crash before the invoice is stored")
            }
        }

        assertNull(numbering.settings()?.lockedAt)
    }

    @Test
    fun `a thousand numbers taken in parallel have no gap and no duplicate`() {
        val numbers = runBlocking(Dispatchers.IO) {
            numbering.save(settings)
            (1..PARALLEL).map { async { next() } }.awaitAll()
        }

        assertEquals((1..PARALLEL).map { "A1-FN-%05d/2026".format(it) }, numbers.sorted())
        assertEquals(PARALLEL.toLong(), runBlocking { numbering.lastCounter(DocumentType.FN, 2026) })
    }

    private companion object {
        const val PARALLEL = 1000
    }
}
