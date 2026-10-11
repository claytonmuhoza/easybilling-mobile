package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.CancelInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationRules
import com.claytonmuhoza.easybilling.domain.invoicing.CorrectionAuthority
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNoteRules
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNoteStorage
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentStamper
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.IssueCreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.RedoSale
import com.claytonmuhoza.easybilling.domain.invoicing.ReplaceInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.stock.InvoiceStock
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the cancellation, the replacement and the credit notes of the invoices (UC-E01 to E03, E07, D51). */
@Module
@InstallIn(SingletonComponent::class)
object CorrectionModule {

    /** @return the cancellations kept in the local database. */
    @Provides
    fun invoiceCancellationRepository(storage: LocalStorage): InvoiceCancellationRepository = storage.cancellations

    /** @return which invoices may be cancelled or replaced now. */
    @Provides
    fun cancellationRules(
        invoices: InvoiceRepository,
        cancellations: InvoiceCancellationRepository,
        drafts: SaleDraftRepository,
    ): CancellationRules = CancellationRules(invoices, cancellations, drafts)

    /** @return who may correct an invoice, alone or with the approval of a manager. */
    @Provides
    fun correctionAuthority(
        access: AccessControl,
        users: UserRepository,
        policy: PermissionPolicy,
    ): CorrectionAuthority = CorrectionAuthority(access, users, policy)

    /** @return the use case cancelling an invoice without replacing it. */
    @Provides
    fun cancelInvoice(
        rules: CancellationRules,
        authority: CorrectionAuthority,
        cancellations: InvoiceCancellationRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): CancelInvoice = CancelInvoice(rules, authority, cancellations, outbox, auditLog, transactions, time)

    /** @return the use case starting the replacement of an invoice. */
    @Provides
    fun replaceInvoice(
        rules: CancellationRules,
        authority: CorrectionAuthority,
        redoSale: RedoSale,
        auditLog: AuditLog,
    ): ReplaceInvoice = ReplaceInvoice(rules, authority, redoSale, auditLog)

    /** @return which invoices may be credited now, and what is left to credit on them. */
    @Provides
    fun creditNoteRules(
        invoices: InvoiceRepository,
        cancellations: InvoiceCancellationRepository,
        drafts: SaleDraftRepository,
    ): CreditNoteRules = CreditNoteRules(invoices, cancellations, drafts)

    /** @return what gives a document its seller, its number and its identifier. */
    @Provides
    fun documentStamper(
        profiles: TaxProfileRepository,
        ebmsSettings: EbmsSettingsRepository,
        vault: EbmsCredentialsVault,
        readiness: CheckBillingReadiness,
        allocateNumber: AllocateInvoiceNumber,
    ): DocumentStamper = DocumentStamper(profiles, ebmsSettings, vault, readiness, allocateNumber)

    /** @return the use case issuing a credit note on an invoice. */
    @Provides
    fun issueCreditNote(
        rules: CreditNoteRules,
        authority: CorrectionAuthority,
        stamper: DocumentStamper,
        invoices: InvoiceRepository,
        outbox: OutboxRepository,
        auditLog: AuditLog,
        stock: InvoiceStock,
        transactions: TransactionRunner,
        time: TimeProvider,
    ): IssueCreditNote {
        val storage = CreditNoteStorage(invoices, outbox, auditLog, stock)
        return IssueCreditNote(rules, authority, stamper, storage, transactions, time)
    }
}
