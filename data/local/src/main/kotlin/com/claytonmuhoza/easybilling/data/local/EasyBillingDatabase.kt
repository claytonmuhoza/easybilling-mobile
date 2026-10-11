package com.claytonmuhoza.easybilling.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.claytonmuhoza.easybilling.data.local.audit.AuditDao
import com.claytonmuhoza.easybilling.data.local.audit.AuditEntryEntity
import com.claytonmuhoza.easybilling.data.local.catalog.ItemDao
import com.claytonmuhoza.easybilling.data.local.catalog.ItemEntity
import com.claytonmuhoza.easybilling.data.local.configuration.DocumentSettingsDao
import com.claytonmuhoza.easybilling.data.local.configuration.DocumentSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.EbmsSettingsDao
import com.claytonmuhoza.easybilling.data.local.configuration.EbmsSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.NumberCounterEntity
import com.claytonmuhoza.easybilling.data.local.configuration.NumberingDao
import com.claytonmuhoza.easybilling.data.local.configuration.NumberingSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.DiscountSettingsDao
import com.claytonmuhoza.easybilling.data.local.configuration.DiscountSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.PrinterSettingsDao
import com.claytonmuhoza.easybilling.data.local.configuration.PrinterSettingsEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxDao
import com.claytonmuhoza.easybilling.data.local.configuration.TaxEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxProfileDao
import com.claytonmuhoza.easybilling.data.local.configuration.TaxProfileVersionEntity
import com.claytonmuhoza.easybilling.data.local.configuration.TaxVersionEntity
import com.claytonmuhoza.easybilling.data.local.customer.CustomerDao
import com.claytonmuhoza.easybilling.data.local.customer.CustomerEntity
import com.claytonmuhoza.easybilling.data.local.identity.UserDao
import com.claytonmuhoza.easybilling.data.local.identity.UserEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceCancellationDao
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceCancellationEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceDao
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceEntity
import com.claytonmuhoza.easybilling.data.local.stock.StockMovementDao
import com.claytonmuhoza.easybilling.data.local.stock.StockMovementEntity
import com.claytonmuhoza.easybilling.data.local.stock.StockMovementStatusEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceLineEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceListDao
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceSellerDao
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceSellerEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.InvoiceStatusEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.SaleDraftDao
import com.claytonmuhoza.easybilling.data.local.invoicing.SaleDraftEntity
import com.claytonmuhoza.easybilling.data.local.invoicing.SaleDraftLineEntity
import com.claytonmuhoza.easybilling.data.local.transmission.InvoiceReceiptEntity
import com.claytonmuhoza.easybilling.data.local.transmission.ObrExchangeDao
import com.claytonmuhoza.easybilling.data.local.transmission.ObrExchangeEntity
import com.claytonmuhoza.easybilling.data.local.transmission.OutboxDao
import com.claytonmuhoza.easybilling.data.local.transmission.OutboxEntryEntity
import com.claytonmuhoza.easybilling.data.local.transmission.ReceiptDao

/**
 * The local database.
 *
 * Until the first stable version, the version is raised at each schema change and an older
 * database is erased on update (decision D30): there is no migration yet. Migrations become
 * mandatory before the first stable version.
 *
 * History: 1 users and audit (step 02); 2 tax profile versions (step 03); 3 EBMS settings and
 * exchanges with the OBR (step 04); 4 taxes and their dated rates (step 05); 5 numbering and its
 * counters (step 06); 6 items of the catalogue (step 07); 7 customers (step 08); 8 sales that are not
 * issued, issued invoices with their status, and send queue (step 09); 9 state of the send queue and
 * acknowledgements of the OBR (step 10); 10 total and search text of the invoices, and seller of the
 * invoices sent again (step 12); 11 settings of the documents (step 13); 12 settings of the printer
 * (step 13); 13 several roles per user, disabled accounts and temporary PINs (step 14a); 14 approval
 * of the discount of a line and cap of the discount (step 14b); 15 cancellations of the invoices,
 * replaced invoice of an invoice and of a sale (step 15a); 16 credited invoice and reason of a credit
 * note (step 15b); 17 stock movements with their status, and what an entry of the send queue is
 * about (step 16a); 18 invoice a stock movement was written with (step 16b).
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
        CustomerEntity::class,
        SaleDraftEntity::class,
        SaleDraftLineEntity::class,
        InvoiceEntity::class,
        InvoiceLineEntity::class,
        InvoiceStatusEntity::class,
        InvoiceSellerEntity::class,
        OutboxEntryEntity::class,
        InvoiceReceiptEntity::class,
        DocumentSettingsEntity::class,
        PrinterSettingsEntity::class,
        DiscountSettingsEntity::class,
        InvoiceCancellationEntity::class,
        StockMovementEntity::class,
        StockMovementStatusEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
// One accessor per DAO: the list grows with the schema.
@Suppress("TooManyFunctions")
internal abstract class EasyBillingDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun auditDao(): AuditDao

    abstract fun taxProfileDao(): TaxProfileDao

    abstract fun ebmsSettingsDao(): EbmsSettingsDao

    abstract fun obrExchangeDao(): ObrExchangeDao

    abstract fun taxDao(): TaxDao

    abstract fun numberingDao(): NumberingDao

    abstract fun itemDao(): ItemDao

    abstract fun customerDao(): CustomerDao

    abstract fun saleDraftDao(): SaleDraftDao

    abstract fun invoiceDao(): InvoiceDao

    abstract fun invoiceSellerDao(): InvoiceSellerDao

    abstract fun invoiceListDao(): InvoiceListDao

    abstract fun outboxDao(): OutboxDao

    abstract fun receiptDao(): ReceiptDao

    abstract fun documentSettingsDao(): DocumentSettingsDao

    abstract fun printerSettingsDao(): PrinterSettingsDao

    abstract fun discountSettingsDao(): DiscountSettingsDao

    abstract fun invoiceCancellationDao(): InvoiceCancellationDao

    abstract fun stockMovementDao(): StockMovementDao
}

/**
 * Makes some tables append only at the database level: even code that bypasses the repositories
 * cannot modify or delete a row. The triggers are checked at each opening, so a database
 * recreated after a schema change gets them too.
 */
internal object AppendOnlyTables : RoomDatabase.Callback() {
    private val tables = listOf(
        "audit_entries",
        "tax_profile_versions",
        "tax_versions",
        "invoices",
        "invoice_lines",
        "invoice_receipts",
        "stock_movements",
    )

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
