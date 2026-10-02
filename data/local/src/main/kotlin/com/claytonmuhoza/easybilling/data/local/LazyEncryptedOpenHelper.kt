package com.claytonmuhoza.easybilling.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the SQLCipher database only when it is first used.
 *
 * Room creates its open helper as soon as the database object is built, possibly on the main
 * thread. Reading the passphrase and loading the native library are slow, so they wait for the
 * first query, which Room always runs on a background thread.
 */
internal class LazyEncryptedOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val passphrase: () -> ByteArray,
) : SupportSQLiteOpenHelper {

    private var writeAheadLogging: Boolean? = null

    private val delegate: SupportSQLiteOpenHelper by lazy {
        System.loadLibrary("sqlcipher")
        SupportOpenHelperFactory(passphrase()).create(configuration).also { helper ->
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
        }
    }

    override val databaseName: String?
        get() = configuration.name

    override val writableDatabase: SupportSQLiteDatabase
        get() = delegate.writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = delegate.readableDatabase

    /** Room sets this right after creating the helper: remember it for when the database opens. */
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLogging = enabled
    }

    override fun close() = delegate.close()
}
