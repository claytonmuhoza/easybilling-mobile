package com.claytonmuhoza.easybilling.di

import android.content.Context
import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.document.BuildInvoiceDocument
import com.claytonmuhoza.easybilling.domain.document.PrintInvoice
import com.claytonmuhoza.easybilling.domain.document.PrintTestPage
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.ReceiptPrinter
import com.claytonmuhoza.easybilling.platform.printing.BluetoothReceiptPrinter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Builds the printing of the tickets: the settings of the printer and the use cases. */
@Module
@InstallIn(SingletonComponent::class)
object PrinterModule {

    /** @return the settings of the printer kept in the local database. */
    @Provides
    fun printerSettingsRepository(storage: LocalStorage): PrinterSettingsRepository = storage.printerSettings

    /** @return the use case printing the ticket of an invoice. */
    @Provides
    fun printInvoice(
        build: BuildInvoiceDocument,
        settings: PrinterSettingsRepository,
        printer: ReceiptPrinter,
        auditLog: AuditLog,
    ): PrintInvoice = PrintInvoice(build, settings, printer, auditLog)

    /** @return the use case printing a test page. */
    @Provides
    fun printTestPage(settings: PrinterSettingsRepository, printer: ReceiptPrinter): PrintTestPage =
        PrintTestPage(settings, printer)
}

/** Binds the printer, which needs the Bluetooth of a device: the tests replace it. */
@Module
@InstallIn(SingletonComponent::class)
object ReceiptPrinterModule {

    /** @return the Bluetooth printer of the device. */
    @Provides
    fun receiptPrinter(@ApplicationContext context: Context): ReceiptPrinter = BluetoothReceiptPrinter(context)
}
