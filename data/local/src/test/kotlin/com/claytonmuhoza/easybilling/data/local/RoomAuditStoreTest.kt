package com.claytonmuhoza.easybilling.data.local

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditHasher
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.identity.LogoutReason
import com.claytonmuhoza.easybilling.domain.identity.UserId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The audit store on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class RoomAuditStoreTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())
    private val store = storage.auditStore
    private val log = ChainedAuditLog(store, { Instant.parse("2026-10-02T08:00:00.123Z") }, storage.transactions)

    private fun entry(sequence: Long, actor: UserId? = UserId(1)) = AuditEntry(
        sequence = sequence,
        timestamp = Instant.parse("2026-10-02T08:00:00.123Z"),
        actor = actor,
        action = "SESSION_OPENED",
        details = "reason=TEST",
        previousHash = AuditHasher.GENESIS_HASH,
        hash = "hash-$sequence",
    )

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `a new log is empty`() = runTest {
        assertNull(store.last())
        assertEquals(emptyList(), store.readAll())
    }

    @Test
    fun `an entry is read back with every field`() = runTest {
        store.append(entry(1))

        assertEquals(entry(1), store.last())
    }

    @Test
    fun `an entry of the system has no actor`() = runTest {
        store.append(entry(1, actor = null))

        assertNull(store.last()?.actor)
    }

    @Test
    fun `entries are read oldest first and the last one is the newest`() = runTest {
        store.append(entry(1))
        store.append(entry(2))
        store.append(entry(3))

        assertEquals(listOf(1L, 2L, 3L), store.readAll().map { it.sequence })
        assertEquals(3L, store.last()?.sequence)
    }

    @Test
    fun `a sequence number cannot be used twice`() = runTest {
        store.append(entry(1))

        assertFailsWith<SQLiteException> { store.append(entry(1)) }
    }

    @Test
    fun `the chained log is intact after a round trip through the database`() = runTest {
        log.record(AuditEvent.AdministratorCreated(UserId(1)))
        log.record(AuditEvent.SessionOpened(UserId(1)))
        log.record(AuditEvent.SessionClosed(UserId(1), LogoutReason.MANUAL))

        assertEquals(3, store.readAll().size)
        assertEquals(AuditVerification.Intact, log.verify())
    }

    @Test
    fun `the database refuses to modify an entry`() = runTest {
        log.record(AuditEvent.SessionOpened(UserId(1)))

        assertFailsWith<SQLiteException> {
            storage.database.openHelper.writableDatabase
                .execSQL("UPDATE audit_entries SET action = 'SOMETHING_ELSE' WHERE sequence = 1")
        }
        assertEquals("SESSION_OPENED", store.last()?.action)
    }

    @Test
    fun `the database refuses to delete an entry`() = runTest {
        log.record(AuditEvent.SessionOpened(UserId(1)))

        assertFailsWith<SQLiteException> {
            storage.database.openHelper.writableDatabase.execSQL("DELETE FROM audit_entries")
        }
        assertEquals(1, store.readAll().size)
    }
}
