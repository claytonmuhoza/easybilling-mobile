package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.claytonmuhoza.easybilling.data.local.audit.RoomAuditStore
import com.claytonmuhoza.easybilling.data.local.configuration.RoomTaxProfileRepository
import com.claytonmuhoza.easybilling.data.local.identity.RoomUserRepository
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * Entry point of the local storage: gives access to the domain ports backed by the database and
 * hides everything else (Room, SQLCipher, tables).
 */
class LocalStorage private constructor(internal val database: EasyBillingDatabase) {

    /** The users of this device. */
    val users: UserRepository = RoomUserRepository(database.userDao())

    /** The entries of the audit log. */
    val auditStore: AuditStore = RoomAuditStore(database.auditDao())

    /** The versions of the tax profile. */
    val taxProfiles: TaxProfileRepository = RoomTaxProfileRepository(database.taxProfileDao())

    /** Runs several writes as one. */
    val transactions: TransactionRunner = RoomTransactionRunner(database)

    /** Closes the database. The storage cannot be used afterwards. */
    fun close() = database.close()

    /** Ways to open the storage. */
    companion object {
        private const val DATABASE_NAME = "easybilling.db"

        /**
         * Opens the encrypted database of the application. Nothing slow happens here: the
         * database is opened, and [passphrase] is called, on first use and on a background thread.
         *
         * @param context any context of the application.
         * @param name file name of the database.
         * @param passphrase gives the key of the database when it is first needed.
         * @return the storage.
         */
        fun encrypted(
            context: Context,
            name: String = DATABASE_NAME,
            passphrase: () -> ByteArray,
        ): LocalStorage = open(
            Room.databaseBuilder(context.applicationContext, EasyBillingDatabase::class.java, name)
                .openHelperFactory { configuration -> LazyEncryptedOpenHelper(configuration, passphrase) },
        )

        /**
         * Opens an empty, unencrypted database that lives in memory. For tests on the JVM, where
         * the native encryption library cannot be loaded.
         *
         * @param context any context of the application.
         * @return the storage.
         */
        fun inMemory(context: Context): LocalStorage = open(
            Room.inMemoryDatabaseBuilder(context.applicationContext, EasyBillingDatabase::class.java),
        )

        /**
         * Opens an unencrypted database file. For the tests of what happens to an existing file.
         *
         * @param context any context of the application.
         * @param name file name of the database.
         * @return the storage.
         */
        internal fun unencryptedFile(context: Context, name: String): LocalStorage = open(
            Room.databaseBuilder(context.applicationContext, EasyBillingDatabase::class.java, name),
        )

        private fun open(builder: RoomDatabase.Builder<EasyBillingDatabase>) = LocalStorage(
            builder
                // Decision D30: until the first stable version, an older schema is erased.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(AppendOnlyTables)
                .build(),
        )
    }
}
