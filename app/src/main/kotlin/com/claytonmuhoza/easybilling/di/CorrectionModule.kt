package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.CancelInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationRules
import com.claytonmuhoza.easybilling.domain.invoicing.CorrectionAuthority
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.RedoSale
import com.claytonmuhoza.easybilling.domain.invoicing.ReplaceInvoice
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the cancellation and the replacement of the invoices (UC-E01, E02, E07, D51). */
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
}
