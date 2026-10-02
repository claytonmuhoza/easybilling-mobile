package com.claytonmuhoza.easybilling.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.claytonmuhoza.easybilling.data.local.audit.AuditDao
import com.claytonmuhoza.easybilling.data.local.audit.AuditEntryEntity
import com.claytonmuhoza.easybilling.data.local.identity.UserDao
import com.claytonmuhoza.easybilling.data.local.identity.UserEntity

/**
 * The local database. Its schema changes freely until a first version is published: there is no
 * migration yet, and the version stays at 1.
 */
@Database(
    entities = [UserEntity::class, AuditEntryEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class EasyBillingDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun auditDao(): AuditDao
}

/**
 * Makes the audit table append only at the database level: even code that bypasses the
 * repositories cannot modify or delete an entry.
 */
internal object AppendOnlyAuditLog : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        for (operation in listOf("UPDATE", "DELETE")) {
            db.execSQL(
                """
                CREATE TRIGGER audit_entries_no_${operation.lowercase()}
                BEFORE $operation ON audit_entries
                BEGIN SELECT RAISE(ABORT, 'The audit log is append only'); END
                """.trimIndent(),
            )
        }
    }
}
