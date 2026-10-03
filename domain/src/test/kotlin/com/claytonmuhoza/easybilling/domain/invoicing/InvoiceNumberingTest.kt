package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.NumberingProblem
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.InMemoryNumberingRepository
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Numbers of the documents (UC-A06) and identifier of the invoice. */
class InvoiceNumberingTest {

    private val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00Z"))
    private val numbering = InMemoryNumberingRepository()
    private val audit = RecordingAuditLog()
    private val save = SaveNumbering(numbering, audit, DirectTransactionRunner)
    private val allocate = AllocateInvoiceNumber(numbering, time, DirectTransactionRunner)
    private val alice = UserId(1)

    private suspend fun next(type: DocumentType = DocumentType.FN, at: String = "2026-10-03T08:00:00Z") =
        assertIs<AllocateInvoiceNumber.Result.Allocated>(allocate(type, Instant.parse(at))).number

    @Test
    fun `the identifier follows the example of the specification`() {
        // 07:30:22 in Bujumbura is 05:30:22 in UTC.
        val identifier = InvoiceIdentifier.of(
            tin = "4400773244",
            systemId = "ws440077324400027",
            issuedAt = Instant.parse("2021-12-06T05:30:22Z"),
            number = "0001/2021",
        )

        assertEquals("4400773244/ws440077324400027/20211206073022/0001/2021", identifier)
    }

    @Test
    fun `the room left for the number in the identifier depends on the NIF and the system identifier`() {
        assertEquals(46, InvoiceIdentifier.roomForNumber(tin = "4400773244", systemId = "ws440077324400027"))
        assertEquals(28, InvoiceIdentifier.roomForNumber(tin = "1".repeat(15), systemId = "w".repeat(30)))
    }

    @Test
    fun `an identifier longer than 90 characters is refused`() {
        assertFailsWith<IllegalArgumentException> {
            InvoiceIdentifier.of("1".repeat(15), "w".repeat(30), Instant.parse("2026-10-03T08:00:00Z"), "N".repeat(30))
        }
    }

    @Test
    fun `no number is given before the numbering is saved`() = runTest {
        assertEquals(AllocateInvoiceNumber.Result.NotConfigured, allocate(DocumentType.FN, time.now()))
    }

    @Test
    fun `numbers follow each other without gap, one counter per type of document`() = runTest {
        save(NumberingForm(), null, alice)

        assertEquals("FN-00001/2026", next())
        assertEquals("FN-00002/2026", next())
        assertEquals("FA-00001/2026", next(DocumentType.FA))
        assertEquals("FN-00003/2026", next())
        assertEquals("RC-00001/2026", next(DocumentType.RC))
        assertEquals("RHF-00001/2026", next(DocumentType.RHF))
    }

    @Test
    fun `a yearly counter starts again on the first of January in Bujumbura`() = runTest {
        save(NumberingForm(), null, alice)
        assertEquals("FN-00001/2026", next(at = "2026-12-31T21:59:59Z"))

        // 22:30 in UTC on the 31st is already 00:30 on the 1st in Bujumbura.
        assertEquals("FN-00001/2027", next(at = "2026-12-31T22:30:00Z"))
        assertEquals("FN-00002/2027", next(at = "2027-01-02T08:00:00Z"))
    }

    @Test
    fun `a counter that is never reset goes on across years`() = runTest {
        save(NumberingForm(template = "{TYPE}-{NNNNN}", reset = CounterReset.NEVER), null, alice)

        assertEquals("FN-00001", next(at = "2026-12-31T08:00:00Z"))
        assertEquals("FN-00002", next(at = "2027-01-05T08:00:00Z"))
    }

    @Test
    fun `an exhausted counter gives no more number and consumes nothing`() = runTest {
        save(NumberingForm(template = "{TYPE}-{N}/{YYYY}"), null, alice)
        repeat(9) { next() }

        assertEquals(AllocateInvoiceNumber.Result.Exhausted, allocate(DocumentType.FN, time.now()))
        assertEquals(9, numbering.lastCounter(DocumentType.FN, 2026))
        assertEquals("FA-1/2026", next(DocumentType.FA))
    }

    @Test
    fun `the first number locks the numbering`() = runTest {
        save(NumberingForm(), null, alice)
        assertNull(numbering.settings()!!.lockedAt)

        next()

        assertNotNull(numbering.settings()!!.lockedAt)
        assertEquals(SaveNumbering.Result.Locked, save(NumberingForm(template = "{TYPE}/{NNNNNN}/{YYYY}"), null, alice))
        assertEquals("FN-00002/2026", next())
    }

    @Test
    fun `saving checks the template and is audited`() = runTest {
        assertEquals(
            SaveNumbering.Result.Invalid(setOf(NumberingProblem.TYPE_MISSING)),
            save(NumberingForm(template = "{NNNNN}/{YYYY}"), null, alice),
        )
        assertEquals(emptyList(), audit.events)

        assertEquals(SaveNumbering.Result.Saved, save(NumberingForm(), null, alice))
        assertEquals(AuditEvent.NumberingSaved(alice), audit.events.single())
    }

    @Test
    fun `saving checks the room left in the identifier`() = runTest {
        assertEquals(
            SaveNumbering.Result.Invalid(setOf(NumberingProblem.IDENTIFIER_TOO_LONG)),
            save(NumberingForm(), identifierRoom = 10, alice),
        )
    }
}
