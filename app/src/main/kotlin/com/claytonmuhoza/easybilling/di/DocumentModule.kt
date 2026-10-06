package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.documents.FileProviderUris
import com.claytonmuhoza.easybilling.documents.SharedFileUris
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.document.BuildInvoiceDocument
import com.claytonmuhoza.easybilling.domain.document.CreateInvoicePdf
import com.claytonmuhoza.easybilling.domain.document.DocumentSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.InvoicePdfWriter
import com.claytonmuhoza.easybilling.domain.document.SaveDocumentSettings
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import com.claytonmuhoza.easybilling.platform.document.AndroidInvoicePdfWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the documents of the invoices: their content, their settings and their PDF. */
@Module
@InstallIn(SingletonComponent::class)
object DocumentModule {

    /** @return the settings of the documents kept in the local database. */
    @Provides
    fun documentSettingsRepository(storage: LocalStorage): DocumentSettingsRepository = storage.documentSettings

    /** @return the use case saving the settings of the documents. */
    @Provides
    fun saveDocumentSettings(settings: DocumentSettingsRepository): SaveDocumentSettings =
        SaveDocumentSettings(settings)

    /** @return the use case building the document of an invoice. */
    @Provides
    fun buildInvoiceDocument(
        invoices: InvoiceRepository,
        profiles: TaxProfileRepository,
        users: UserRepository,
        receipts: ReceiptArchive,
        settings: DocumentSettingsRepository,
    ): BuildInvoiceDocument = BuildInvoiceDocument(invoices, profiles, users, receipts, settings)

    /** @return the use case creating the PDF of an invoice. */
    @Provides
    fun createInvoicePdf(
        access: AccessControl,
        build: BuildInvoiceDocument,
        writer: InvoicePdfWriter,
        auditLog: AuditLog,
    ): CreateInvoicePdf =
        CreateInvoicePdf(access, build, writer, auditLog)
}

/** Binds what gives another application the right to read a shared file: the tests replace it. */
@Module
@InstallIn(SingletonComponent::class)
object SharedFilesModule {

    /** @return the addresses given by the `FileProvider` of the manifest. */
    @Provides
    fun sharedFileUris(uris: FileProviderUris): SharedFileUris = uris
}

/** Binds the writer of PDF files, which needs a device: the tests replace it. */
@Module
@InstallIn(SingletonComponent::class)
object PdfModule {

    /** @return the writer that draws an invoice with the PDF support of Android. */
    @Provides
    fun invoicePdfWriter(): InvoicePdfWriter = AndroidInvoicePdfWriter()
}
