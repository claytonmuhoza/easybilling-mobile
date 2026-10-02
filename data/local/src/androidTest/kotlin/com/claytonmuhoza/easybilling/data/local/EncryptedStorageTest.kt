package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditVerification
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs on a device or emulator: the native SQLCipher library cannot be loaded on the JVM. */
@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { it.toByte() }
    private val wrongKey = ByteArray(32) { (it + 1).toByte() }

    private val alice = NewUser(
        name = "Alice Administrator",
        role = Role.ADMINISTRATOR,
        credentials = Credentials(
            pinHash = SecretHash(byteArrayOf(1), byteArrayOf(2), 1),
            recoveryHash = SecretHash(byteArrayOf(3), byteArrayOf(4), 1),
        ),
    )

    private fun open(passphrase: ByteArray) = LocalStorage.encrypted(context, DATABASE_NAME) { passphrase.copyOf() }

    private suspend fun writeAliceAndClose() {
        val storage = open(key)
        val log = ChainedAuditLog(storage.auditStore, { Instant.parse("2026-10-02T08:00:00Z") }, storage.transactions)
        storage.transactions.run {
            val user = storage.users.create(alice)
            log.record(AuditEvent.AdministratorCreated(user.id))
        }
        storage.close()
    }

    private fun databaseFileContent(): String =
        String(context.getDatabasePath(DATABASE_NAME).readBytes(), Charsets.ISO_8859_1)

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun the_database_file_is_unreadable_without_the_key() = runTest {
        writeAliceAndClose()

        val content = databaseFileContent()
        assertTrue(content.isNotEmpty())
        assertFalse(content.startsWith("SQLite format 3"), "The file is a plain SQLite database")
        assertFalse("Alice Administrator" in content, "The user name is readable in the file")
        assertFalse("ADMINISTRATOR_CREATED" in content, "The audit log is readable in the file")
        assertFalse("audit_entries" in content, "The table names are readable in the file")
    }

    @Test
    fun the_data_is_read_back_with_the_right_key() = runTest {
        writeAliceAndClose()

        val storage = open(key)
        val log = ChainedAuditLog(storage.auditStore, { Instant.EPOCH }, storage.transactions)
        assertEquals(listOf("Alice Administrator"), storage.users.findAll().map { it.name })
        assertEquals(listOf("ADMINISTRATOR_CREATED"), storage.auditStore.readAll().map { it.action })
        assertEquals(AuditVerification.Intact, log.verify())
        storage.close()
    }

    @Test
    fun the_database_cannot_be_opened_with_a_wrong_key() = runTest {
        writeAliceAndClose()

        val storage = open(wrongKey)
        assertFails { storage.users.count() }
        storage.close()
    }

    @Test
    fun the_key_is_not_requested_before_the_first_query() = runTest {
        var requests = 0
        val storage = LocalStorage.encrypted(context, DATABASE_NAME) {
            requests++
            key.copyOf()
        }
        assertEquals(0, requests)

        storage.users.count()

        assertEquals(1, requests)
        storage.close()
    }

    private companion object {
        const val DATABASE_NAME = "encrypted_storage_test.db"
    }
}
