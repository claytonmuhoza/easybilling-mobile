package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.BillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Where the issue of a sale reads the configuration it copies in the invoice.
 *
 * @property profiles where the tax profile is kept.
 * @property ebmsSettings where the active environment is kept.
 * @property vault where the system identifiers are kept.
 * @property readiness tells whether invoices can be issued.
 * @property discountLimits tells which discounts need the approval of a manager.
 */
class IssueConfiguration(
    val profiles: TaxProfileRepository,
    val ebmsSettings: EbmsSettingsRepository,
    val vault: EbmsCredentialsVault,
    val readiness: CheckBillingReadiness,
    val discountLimits: DiscountLimits,
)

/**
 * Where the issue of a sale writes.
 *
 * @property drafts where the sales that are not issued are kept.
 * @property invoices where the issued invoices are kept.
 * @property outbox the send queue.
 * @property auditLog log that records each issue.
 */
class IssueStorage(
    val drafts: SaleDraftRepository,
    val invoices: InvoiceRepository,
    val outbox: OutboxRepository,
    val auditLog: AuditLog,
)

/**
 * Issues a sale as an invoice (UC-C01, steps 4 and 5): the number, the invoice, its entry in the
 * send queue and the audit entry are written together or not at all, and the sale leaves the
 * drafts. The invoice copies the customer, the lines, the rates and the amounts of that moment.
 *
 * @param configuration what the invoice copies from the configuration.
 * @param storage where the invoice is written.
 * @param priceSale computes the sale with the prices and the rates of now.
 * @param allocateNumber gives the next number.
 * @param transactions makes the issue a single write.
 * @param time source of the date of the invoice.
 */
class IssueSale(
    private val access: AccessControl,
    private val configuration: IssueConfiguration,
    private val storage: IssueStorage,
    private val priceSale: PriceSale,
    private val allocateNumber: AllocateInvoiceNumber,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of an issue. */
    sealed interface Result {
        /** [invoice] is issued, waiting to be sent to the OBR. */
        data class Issued(val invoice: Invoice) : Result

        /** There is no sale with that identifier: it was issued or deleted meanwhile. */
        data object NotFound : Result

        /** The configuration is not complete (see [CheckBillingReadiness]); nothing was issued. */
        data class NotReady(val readiness: BillingReadiness) : Result

        /** The sale cannot be issued as it is; nothing was issued. */
        data class Invalid(val sale: PricedSale) : Result

        /**
         * The discount of the lines of [itemIds] goes beyond the cap of the user without the
         * approval of a manager (UC-C03); nothing was issued.
         */
        data class DiscountNotApproved(val itemIds: Set<ItemId>) : Result

        /** The counter reached the last number its digits allow; nothing was issued. */
        data object NumbersExhausted : Result

        /** The number does not fit in the 90 characters of the identifier; nothing was issued. */
        data object IdentifierTooLong : Result
    }

    /** Leaves the transaction without storing anything: the number is given back. */
    private class Aborted(val result: Result) : Exception()

    /**
     * @param draftId the sale to issue.
     * @param actor the user who issues it.
     * @return the invoice, or why nothing was issued.
     * @throws com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException if [actor] is not allowed to.
     */
    suspend operator fun invoke(draftId: DraftId, actor: UserId): Result {
        access.require(actor, Permission.SELL)
        return allowed(draftId, actor)
    }

    private suspend fun allowed(draftId: DraftId, actor: UserId): Result =
        try {
            transactions.run { issued(draftId, actor) }
        } catch (aborted: Aborted) {
            aborted.result
        }

    private suspend fun issued(draftId: DraftId, actor: UserId): Result {
        val draft = storage.drafts.find(draftId) ?: return Result.NotFound
        val seller = seller()
        val sale = issuable(draft, actor)

        // The OBR reads the date to the second.
        val issuedAt = time.now().truncatedTo(ChronoUnit.SECONDS)
        val number = number(issuedAt)
        if (number.length > InvoiceIdentifier.roomForNumber(seller.tin, seller.systemId)) {
            throw Aborted(Result.IdentifierTooLong)
        }
        val content = InvoiceContent(
            type = DocumentType.FN,
            number = number,
            identifier = InvoiceIdentifier.of(seller.tin, seller.systemId, issuedAt, number),
            issuedAt = issuedAt,
            profileVersion = seller.profileVersion,
            environment = seller.environment,
            customer = sale.customer.let {
                InvoiceCustomer(it.id, it.details.name, it.details.tin?.number, it.details.address, it.details.vatPayer)
            },
            paymentType = draft.paymentType,
            currency = Currency.BIF,
            lines = sale.lines.map { it.toInvoiceLine(sale) },
            issuedBy = actor,
        )
        val invoice = storage.invoices.create(content)
        storage.outbox.enqueue(OutboxOperation.SendInvoice(invoice.id), issuedAt)
        storage.drafts.delete(draft.id)
        storage.auditLog.record(AuditEvent.InvoiceIssued(actor, invoice.id, number))
        return Result.Issued(invoice)
    }

    /** @return [draft] priced, once nothing in it nor in its discounts prevents its issue by [actor]. */
    private suspend fun issuable(draft: SaleDraft, actor: UserId): PricedSale {
        val sale = priceSale(draft)
        if (!sale.issuable) throw Aborted(Result.Invalid(sale))
        val unapproved = configuration.discountLimits.unapproved(actor, sale)
        if (unapproved.isNotEmpty()) throw Aborted(Result.DiscountNotApproved(unapproved.map { it.item.id }.toSet()))
        return sale
    }

    /** What the invoice takes from the configuration. */
    private class Seller(
        val tin: String,
        val profileVersion: Int,
        val environment: EbmsEnvironment,
        val systemId: String,
    )

    private suspend fun seller(): Seller {
        val readiness = configuration.readiness()
        val environment = configuration.ebmsSettings.read().activeEnvironment
        val systemId = configuration.vault.read(environment)?.systemId
        val profile = configuration.profiles.current()
        if (!readiness.ready || profile == null || systemId == null) throw Aborted(Result.NotReady(readiness))
        return Seller(profile.profile.tin, profile.number, environment, systemId)
    }

    private suspend fun number(issuedAt: Instant): String =
        when (val allocation = allocateNumber(DocumentType.FN, issuedAt)) {
            is AllocateInvoiceNumber.Result.Allocated -> allocation.number
            AllocateInvoiceNumber.Result.Exhausted -> throw Aborted(Result.NumbersExhausted)
            // The readiness already asked for a numbering.
            AllocateInvoiceNumber.Result.NotConfigured -> error("No numbering although the configuration is ready")
        }

    private fun PricedLine.toInvoiceLine(sale: PricedSale) = InvoiceLine(
        itemId = item.id,
        itemCode = item.code,
        designation = item.details.designation,
        unit = item.details.unit,
        stockTracked = item.details.stockTracked,
        quantity = line.quantity,
        listUnitPrice = item.details.price,
        priceMode = item.details.priceMode,
        discount = line.discount,
        vatRate = vatRate(sale.registrations),
        amounts = checkNotNull(amounts) { "An issuable line has its amounts" },
    )
}
