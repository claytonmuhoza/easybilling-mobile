package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.claytonmuhoza.easybilling.data.local.audit.RoomAuditStore
import com.claytonmuhoza.easybilling.data.local.catalog.RoomItemRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomDocumentSettingsRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomEbmsSettingsRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomNumberingRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomDiscountSettingsRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomPrinterSettingsRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomTaxProfileRepository
import com.claytonmuhoza.easybilling.data.local.configuration.RoomTaxRepository
import com.claytonmuhoza.easybilling.data.local.customer.RoomCustomerRepository
import com.claytonmuhoza.easybilling.data.local.identity.RoomUserRepository
import com.claytonmuhoza.easybilling.data.local.invoicing.RoomInvoiceCancellationRepository
import com.claytonmuhoza.easybilling.data.local.invoicing.RoomInvoiceListing
import com.claytonmuhoza.easybilling.data.local.invoicing.RoomInvoiceRepository
import com.claytonmuhoza.easybilling.data.local.invoicing.RoomSaleDraftRepository
import com.claytonmuhoza.easybilling.data.local.transmission.RoomExchangeLog
import com.claytonmuhoza.easybilling.data.local.stock.RoomInventoryRepository
import com.claytonmuhoza.easybilling.data.local.stock.RoomStockRepository
import com.claytonmuhoza.easybilling.data.local.stock.RoomStockSettingsRepository
import com.claytonmuhoza.easybilling.data.local.transmission.RoomOutboxRepository
import com.claytonmuhoza.easybilling.data.local.transmission.RoomReceiptArchive
import com.claytonmuhoza.easybilling.domain.audit.AuditStore
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.document.DocumentSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettingsRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.stock.InventoryRepository
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.domain.stock.StockSettingsRepository
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive

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

    /** The tax definitions and their rates. */
    val taxes: TaxRepository = RoomTaxRepository(database.taxDao())

    /** The items of the catalogue. */
    val items: ItemRepository = RoomItemRepository(database.itemDao())

    /** The customers. */
    val customers: CustomerRepository = RoomCustomerRepository(database)

    /** The sales that are not issued yet. */
    val saleDrafts: SaleDraftRepository = RoomSaleDraftRepository(database)

    /** The issued invoices. */
    val invoices: InvoiceRepository = RoomInvoiceRepository(database)

    /** The cancellations of the invoices, the last one asked for each. */
    val cancellations: InvoiceCancellationRepository =
        RoomInvoiceCancellationRepository(database.invoiceCancellationDao())

    /** The issued invoices as the list shows them. */
    val invoiceListing: InvoiceListing = RoomInvoiceListing(database)

    /** The operations to send to the OBR. */
    val outbox: OutboxRepository = RoomOutboxRepository(database.outboxDao())

    /** The stock movements and their status. */
    val stock: StockRepository = RoomStockRepository(database)

    /** What was counted during the inventory in progress. */
    val inventory: InventoryRepository = RoomInventoryRepository(database)

    /** The reorder thresholds of the items. */
    val stockSettings: StockSettingsRepository = RoomStockSettingsRepository(database)

    /** The acknowledgements of the OBR. */
    val receipts: ReceiptArchive = RoomReceiptArchive(database.receiptDao())

    /** The settings of the documents: footer, logo, QR code. */
    val documentSettings: DocumentSettingsRepository = RoomDocumentSettingsRepository(database.documentSettingsDao())

    /** The printer of the tickets and how it prints. */
    val printerSettings: PrinterSettingsRepository = RoomPrinterSettingsRepository(database.printerSettingsDao())

    /** The cap of the discount a cashier gives alone. */
    val discountSettings: DiscountSettingsRepository = RoomDiscountSettingsRepository(database.discountSettingsDao())

    /** The numbering of the documents and its counters. */
    val numbering: NumberingRepository = RoomNumberingRepository(database.numberingDao())

    /** The settings of the connection to the EBMS, except the credentials. */
    val ebmsSettings: EbmsSettingsRepository = RoomEbmsSettingsRepository(database.ebmsSettingsDao())

    /** The exchanges with the OBR. */
    val exchangeLog: ExchangeLog = RoomExchangeLog(database.obrExchangeDao())

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
