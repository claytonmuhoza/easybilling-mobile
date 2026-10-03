package com.claytonmuhoza.easybilling.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.claytonmuhoza.easybilling.data.local.audit.AuditDao
import com.claytonmuhoza.easybilling.data.local.audit.AuditEntryEntity
import com.claytonmuhoza.easybilling.data.local.catalog.ItemDao
import com.claytonmuhoza.easybilling.data.local.catalog.ItemEntity
import com.claytonmuhoza.easybilling.data.local.configuration.EbmsSettingsDao
import com.claytonmuhoza.easybilling.data.local.configuration.EbmsSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.NumberCounterEntity
import com.claytonmuhoza.easybilling.data.local.configuration.NumberingDao
import com.claytonmuhoza.easybilling.data.local.configuration.NumberingSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxDao
import com.claytonmuhoza.easybilling.data.local.configuration.TaxEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxProfileDao
import com.claytonmuhoza.easybilling.data.local.configuration.TaxProfileVersionEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxVersionEntity
import com.claytonmuhoza.easybilling.data.local.identity.UserDao
import com.claytonmuhoza.easybilling.data.local.identity.UserEntity
import com.claytonmuhoza.easybilling.data.local.transmission.ObrExchangeDao
import com.claytonmuhoza.easybilling.data.local.transmission.ObrExchangeEntity

/**
 * The local database.
 *
 * Until the first stable version, the version is raised at each schema change and an older
 * database is erased on update (decision D30): there is no migration yet. Migrations become
 * mandatory before the first stable version.
 *
 * History: 1 users and audit (step 02); 2 tax profile versions (step 03); 3 EBMS settings and
 * exchanges with the OBR (step 04); 4 taxes and their dated rates (step 05); 5 numbering and its
 * counters (step 06); 6 items of the catalogue (step 07).
 */
@Database(
    entities = [
        UserEntity::class,
        AuditEntryEntity::class,
        TaxProfileVersionEntity::class,
        EbmsSettingsEntity::class,
        ObrExchangeEntity::class,
        TaxEntity::class,
        TaxVersionEntity::class,
        NumberingSettingsEntity::class,
        NumberCounterEntity::class,
        ItemEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
internal abstract class EasyBillingDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun auditDao(): AuditDao

    abstract fun taxProfileDao(): TaxProfileDao

    abstract fun ebmsSettingsDao(): EbmsSettingsDao

    abstract fun obrExchangeDao(): ObrExchangeDao

    abstract fun taxDao(): TaxDao

    abstract fun numberingDao(): NumberingDao

    abstract fun itemDao(): ItemDao
}

/**
 * Makes some tables append only at the database level: even code that bypasses the repositories
 * cannot modify or delete a row. The triggers are checked at each opening, so a database
 * recreated after a schema change gets them too.
 */
internal object AppendOnlyTables : RoomDatabase.Callback() {
    private val tables = listOf("audit_entries", "tax_profile_versions", "tax_versions")

    override fun onOpen(db: SupportSQLiteDatabase) {
        for (table in tables) {
            for (operation in listOf("UPDATE", "DELETE")) {
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS ${table}_no_${operation.lowercase()}
                    BEFORE $operation ON $table
                    BEGIN SELECT RAISE(ABORT, 'The table $table is append only'); END
                    """.trimIndent(),
                )
            }
        }
    }
}
