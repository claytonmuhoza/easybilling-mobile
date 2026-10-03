package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Until the first stable version, a database with an older schema is erased when the application
 * is updated (decision D30): the update must not crash, and the new database must be complete.
 */
@RunWith(AndroidJUnit4::class)
class SchemaChangeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "schema-change-test.db"
    private var storage: LocalStorage? = null

    @After
    fun cleanUp() {
        storage?.close()
        context.deleteDatabase(name)
    }

    /** Creates a database file as an older version of the application left it. */
    private fun createOldDatabase(version: Int) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
            .use { db ->
                db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
                db.execSQL("INSERT INTO users (name) VALUES ('Alice')")
                db.version = version
            }
    }

    @Test
    fun `a database of an older schema is erased and recreated complete`() = runTest {
        createOldDatabase(version = 1)

        val opened = LocalStorage.unencryptedFile(context, name).also { storage = it }

        assertEquals(0, opened.users.count())
        assertNull(opened.taxProfiles.current())
        assertEquals(emptyList(), opened.auditStore.readAll())
        assertEquals(0, opened.customers.count(archived = false))
    }

    @Test
    fun `the recreated audit log is still append only`() = runTest {
        createOldDatabase(version = 1)
        val opened = LocalStorage.unencryptedFile(context, name).also { storage = it }
        val log = ChainedAuditLog(opened.auditStore, { Instant.parse("2026-10-03T08:00:00Z") }, opened.transactions)
        log.record(AuditEvent.SessionOpened(UserId(1)))

        assertFailsWith<Exception> {
            opened.database.openHelper.writableDatabase.execSQL("DELETE FROM audit_entries")
        }
        assertEquals(1, opened.auditStore.readAll().size)
    }
}
