package com.claytonmuhoza.easybilling.domain.audit

import com.claytonmuhoza.easybilling.domain.identity.LogoutReason
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.InMemoryAuditStore
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChainedAuditLogTest {

    private val time = MutableTimeProvider()
    private val store = InMemoryAuditStore()
    private val log = ChainedAuditLog(store, time, DirectTransactionRunner)
    private val alice = UserId(1)

    private suspend fun recordThree() {
        log.record(AuditEvent.AdministratorCreated(alice))
        time.advance(Duration.ofSeconds(5))
        log.record(AuditEvent.SessionOpened(alice))
        time.advance(Duration.ofMinutes(5))
        log.record(AuditEvent.SessionClosed(alice, LogoutReason.INACTIVITY))
    }

    @Test
    fun `the first entry is chained to the genesis hash`() = runTest {
        log.record(AuditEvent.AdministratorCreated(alice))

        val entry = store.entries.single()
        assertEquals(1, entry.sequence)
        assertEquals(AuditHasher.GENESIS_HASH, entry.previousHash)
        assertEquals(Instant.parse("2026-10-02T08:00:00Z"), entry.timestamp)
        assertEquals(alice, entry.actor)
        assertEquals("ADMINISTRATOR_CREATED", entry.action)
    }

    @Test
    fun `each entry is chained to the previous one`() = runTest {
        recordThree()

        val (first, second, third) = store.entries
        assertEquals(listOf(1L, 2L, 3L), store.entries.map { it.sequence })
        assertEquals(first.hash, second.previousHash)
        assertEquals(second.hash, third.previousHash)
        assertEquals("reason=INACTIVITY", third.details)
    }

    @Test
    fun `a hash is 64 hexadecimal characters and depends on the content`() = runTest {
        recordThree()

        val hashes = store.entries.map { it.hash }
        hashes.forEach { assertEquals(true, Regex("[0-9a-f]{64}").matches(it)) }
        assertEquals(3, hashes.toSet().size)
    }

    @Test
    fun `timestamps are truncated to the millisecond so they survive storage`() = runTest {
        time.instant = Instant.parse("2026-10-02T08:00:00.123456789Z")

        log.record(AuditEvent.SessionOpened(alice))

        assertEquals(Instant.parse("2026-10-02T08:00:00.123Z"), store.entries.single().timestamp)
        assertEquals(AuditVerification.Intact, log.verify())
    }

    @Test
    fun `an empty log is intact`() = runTest {
        assertEquals(AuditVerification.Intact, log.verify())
    }

    @Test
    fun `an untouched log is intact`() = runTest {
        recordThree()

        assertEquals(AuditVerification.Intact, log.verify())
    }

    @Test
    fun `a modified entry is detected`() = runTest {
        recordThree()
        store.entries[1] = store.entries[1].copy(action = "SOMETHING_ELSE")

        assertEquals(AuditVerification.Tampered(sequence = 2), log.verify())
    }

    @Test
    fun `a modified timestamp is detected`() = runTest {
        recordThree()
        store.entries[0] = store.entries[0].copy(timestamp = store.entries[0].timestamp.plusSeconds(1))

        assertEquals(AuditVerification.Tampered(sequence = 1), log.verify())
    }

    @Test
    fun `a removed entry is detected`() = runTest {
        recordThree()
        store.entries.removeAt(1)

        assertEquals(AuditVerification.Tampered(sequence = 3), log.verify())
    }

    @Test
    fun `a removed first entry is detected`() = runTest {
        recordThree()
        store.entries.removeAt(0)

        assertEquals(AuditVerification.Tampered(sequence = 2), log.verify())
    }

    @Test
    fun `an entry rewritten with a recomputed hash breaks the next link`() = runTest {
        recordThree()
        val forged = store.entries[1].copy(action = "SOMETHING_ELSE").let {
            it.copy(hash = AuditHasher.hash(it))
        }
        store.entries[1] = forged

        assertEquals(AuditVerification.Tampered(sequence = 3), log.verify())
    }

    @Test
    fun `fields cannot be shifted from one to the next without changing the hash`() {
        val base = AuditEntry(
            sequence = 1,
            timestamp = Instant.parse("2026-10-02T08:00:00Z"),
            actor = alice,
            action = "AB",
            details = "C",
            previousHash = AuditHasher.GENESIS_HASH,
            hash = "",
        )

        assertNotEquals(AuditHasher.hash(base), AuditHasher.hash(base.copy(action = "A", details = "BC")))
    }
}
