package com.claytonmuhoza.easybilling.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An action and its audit entry are stored together or not at all. */
@RunWith(AndroidJUnit4::class)
class RoomTransactionRunnerTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val log = ChainedAuditLog(storage.auditStore, { now }, storage.transactions)

    private val alice = NewUser(
        name = "Alice",
        role = Role.ADMINISTRATOR,
        credentials = Credentials(
            pinHash = SecretHash(byteArrayOf(1), byteArrayOf(2), 1),
            recoveryHash = SecretHash(byteArrayOf(3), byteArrayOf(4), 1),
        ),
    )

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `the block result is returned and its writes are stored`() = runTest {
        val user = storage.transactions.run {
            storage.users.create(alice).also { log.record(AuditEvent.AdministratorCreated(it.id)) }
        }

        assertEquals(listOf(user.id), storage.users.findAll().map { it.id })
        assertEquals(listOf("ADMINISTRATOR_CREATED"), storage.auditStore.readAll().map { it.action })
    }

    @Test
    fun `a failure after the writes stores nothing`() = runTest {
        assertFailsWith<IllegalStateException> {
            storage.transactions.run {
                val user = storage.users.create(alice)
                log.record(AuditEvent.AdministratorCreated(user.id))
                error("Simulated crash before the end of the transaction")
            }
        }

        assertEquals(0, storage.users.count())
        assertEquals(emptyList(), storage.auditStore.readAll())
    }

    @Test
    fun `a failure of the audit write cancels the action`() = runTest {
        log.record(AuditEvent.SessionOpened(UserId(1)))
        val duplicate = storage.auditStore.readAll().single()

        assertFailsWith<Exception> {
            storage.transactions.run {
                storage.users.create(alice)
                storage.auditStore.append(duplicate)
            }
        }

        assertEquals(0, storage.users.count())
    }
}
