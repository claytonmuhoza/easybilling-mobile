package com.claytonmuhoza.easybilling.domain.audit

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.LogoutReason
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Something worth tracing: who did what. The time is added by the [AuditLog].
 *
 * An event never carries a secret (PIN, recovery code, password, token).
 */
sealed interface AuditEvent {
    /** The user who acted, or null for an action of the system. */
    val actor: UserId?

    /** Stable code of the action, stored in the log. Never rename one: old entries keep it. */
    val action: String

    /** Extra facts about the action, as `key=value` pairs separated by `;`. */
    val details: String get() = ""

    /** The administrator account was created by the first launch assistant. */
    data class AdministratorCreated(override val actor: UserId) : AuditEvent {
        override val action get() = "ADMINISTRATOR_CREATED"
    }

    /** A user logged in. */
    data class SessionOpened(override val actor: UserId) : AuditEvent {
        override val action get() = "SESSION_OPENED"
    }

    /** A session ended, by the user or by inactivity. */
    data class SessionClosed(override val actor: UserId, val reason: LogoutReason) : AuditEvent {
        override val action get() = "SESSION_CLOSED"
        override val details get() = "reason=$reason"
    }

    /** A wrong PIN was typed. */
    data class PinRejected(override val actor: UserId) : AuditEvent {
        override val action get() = "PIN_REJECTED"
    }

    /** A wrong recovery code was typed. */
    data class RecoveryCodeRejected(override val actor: UserId) : AuditEvent {
        override val action get() = "RECOVERY_CODE_REJECTED"
    }

    /** Too many wrong secrets locked the account until [until]. */
    data class AccountLocked(override val actor: UserId, val until: Instant) : AuditEvent {
        override val action get() = "ACCOUNT_LOCKED"
        override val details get() = "until=$until"
    }

    /** The PIN was replaced with the recovery code. */
    data class PinReset(override val actor: UserId) : AuditEvent {
        override val action get() = "PIN_RESET"
    }

    /** The account [user] was created with [roles]. */
    data class UserCreated(override val actor: UserId, val user: UserId, val roles: Set<Role>) : AuditEvent {
        override val action get() = "USER_CREATED"
        override val details get() = "user=${user.value};roles=${roles.sorted().joinToString(",")}"
    }

    /** The name or the roles of [user] were changed; [roles] are the new ones. */
    data class UserUpdated(override val actor: UserId, val user: UserId, val roles: Set<Role>) : AuditEvent {
        override val action get() = "USER_UPDATED"
        override val details get() = "user=${user.value};roles=${roles.sorted().joinToString(",")}"
    }

    /** [user] was disabled, or enabled again when [active] is true. */
    data class UserActiveChanged(override val actor: UserId, val user: UserId, val active: Boolean) : AuditEvent {
        override val action get() = "USER_ACTIVE_CHANGED"
        override val details get() = "user=${user.value};active=$active"
    }

    /** An administrator gave a new temporary PIN to [user]. */
    data class UserPinReset(override val actor: UserId, val user: UserId) : AuditEvent {
        override val action get() = "USER_PIN_RESET"
        override val details get() = "user=${user.value}"
    }

    /** A user replaced a temporary PIN with one of their own. */
    data class TemporaryPinChanged(override val actor: UserId) : AuditEvent {
        override val action get() = "TEMPORARY_PIN_CHANGED"
    }

    /** A user asked for something none of their roles allows. */
    data class PermissionDenied(override val actor: UserId, val permission: Permission) : AuditEvent {
        override val action get() = "PERMISSION_DENIED"
        override val details get() = "permission=$permission"
    }

    /** The user approved [sensitiveAction], which [requester] asked for, with their own PIN. */
    data class SensitiveActionAuthorized(
        override val actor: UserId,
        val requester: UserId,
        val sensitiveAction: SensitiveAction,
    ) : AuditEvent {
        override val action get() = "SENSITIVE_ACTION_AUTHORIZED"
        override val details get() = "requester=${requester.value};${sensitiveAction.details}"
    }

    /** The approval of [sensitiveAction] by [approver] was refused: wrong PIN or locked account. */
    data class SensitiveActionRefused(
        override val actor: UserId,
        val approver: UserId,
        val sensitiveAction: SensitiveAction,
    ) : AuditEvent {
        override val action get() = "SENSITIVE_ACTION_REFUSED"
        override val details get() = "approver=${approver.value};${sensitiveAction.details}"
    }

    /** The cap of the discount a cashier gives alone became [cap] percent; null for none. */
    data class DiscountCapChanged(override val actor: UserId, val cap: BigDecimal?) : AuditEvent {
        override val action get() = "DISCOUNT_CAP_CHANGED"
        override val details get() = "cap=${cap?.stripTrailingZeros()?.toPlainString() ?: "none"}"
    }

    /** The address and the credentials of [environment] were saved. No secret is kept here. */
    data class EbmsConnectionSaved(override val actor: UserId, val environment: EbmsEnvironment) : AuditEvent {
        override val action get() = "EBMS_CONNECTION_SAVED"
        override val details get() = "environment=$environment"
    }

    /** The calls to the EBMS now go to [to] instead of [from]. */
    data class EbmsEnvironmentChanged(
        override val actor: UserId,
        val from: EbmsEnvironment,
        val to: EbmsEnvironment,
    ) : AuditEvent {
        override val action get() = "EBMS_ENVIRONMENT_CHANGED"
        override val details get() = "from=$from;to=$to"
    }

    /** The connection to [environment] was tested; [outcome] is the code of the result. */
    data class EbmsConnectionTested(
        override val actor: UserId,
        val environment: EbmsEnvironment,
        val outcome: String,
    ) : AuditEvent {
        override val action get() = "EBMS_CONNECTION_TESTED"
        override val details get() = "environment=$environment;outcome=$outcome"
    }

    /** The exchanges with the OBR are now kept [days] days. */
    data class ExchangeRetentionChanged(override val actor: UserId, val days: Int) : AuditEvent {
        override val action get() = "EXCHANGE_RETENTION_CHANGED"
        override val details get() = "days=$days"
    }

    /** The tax [tax], of kind [kind], was created. */
    data class TaxCreated(override val actor: UserId, val tax: TaxId, val kind: TaxKind) : AuditEvent {
        override val action get() = "TAX_CREATED"
        override val details get() = "tax=${tax.value};kind=$kind"
    }

    /** A new rate of [tax] applies from [validFrom]. */
    data class TaxRateScheduled(override val actor: UserId, val tax: TaxId, val validFrom: LocalDate) : AuditEvent {
        override val action get() = "TAX_RATE_SCHEDULED"
        override val details get() = "tax=${tax.value};from=$validFrom"
    }

    /** [tax] was archived, or brought back when [archived] is false. */
    data class TaxArchived(override val actor: UserId, val tax: TaxId, val archived: Boolean) : AuditEvent {
        override val action get() = "TAX_ARCHIVED"
        override val details get() = "tax=${tax.value};archived=$archived"
    }

    /** Version [version] of the tax profile was saved. */
    data class TaxProfileSaved(override val actor: UserId, val version: Int) : AuditEvent {
        override val action get() = "TAX_PROFILE_SAVED"
        override val details get() = "version=$version"
    }

    /** The numbering of the documents was saved. */
    data class NumberingSaved(override val actor: UserId) : AuditEvent {
        override val action get() = "NUMBERING_SAVED"
    }

    /** The item [item] was created with the code [code]. */
    data class ItemCreated(override val actor: UserId, val item: ItemId, val code: String) : AuditEvent {
        override val action get() = "ITEM_CREATED"
        override val details get() = "item=${item.value};code=$code"
    }

    /** The item [item] was changed. */
    data class ItemUpdated(override val actor: UserId, val item: ItemId) : AuditEvent {
        override val action get() = "ITEM_UPDATED"
        override val details get() = "item=${item.value}"
    }

    /** [item] was archived, or brought back when [archived] is false. */
    data class ItemArchived(override val actor: UserId, val item: ItemId, val archived: Boolean) : AuditEvent {
        override val action get() = "ITEM_ARCHIVED"
        override val details get() = "item=${item.value};archived=$archived"
    }

    /** The customer [customer] was created. */
    data class CustomerCreated(override val actor: UserId, val customer: CustomerId) : AuditEvent {
        override val action get() = "CUSTOMER_CREATED"
        override val details get() = "customer=${customer.value}"
    }

    /** The customer [customer] was changed. */
    data class CustomerUpdated(override val actor: UserId, val customer: CustomerId) : AuditEvent {
        override val action get() = "CUSTOMER_UPDATED"
        override val details get() = "customer=${customer.value}"
    }

    /** [customer] was archived, or brought back when [archived] is false. */
    data class CustomerArchived(
        override val actor: UserId,
        val customer: CustomerId,
        val archived: Boolean,
    ) : AuditEvent {
        override val action get() = "CUSTOMER_ARCHIVED"
        override val details get() = "customer=${customer.value};archived=$archived"
    }

    /** The OBR acknowledged the invoice [invoice] under [registeredNumber]; empty after a lost answer. */
    data class InvoiceRegistered(val invoice: InvoiceId, val registeredNumber: String) : AuditEvent {
        override val actor: UserId? get() = null
        override val action get() = "INVOICE_REGISTERED"
        override val details get() = "invoice=${invoice.value};registered=$registeredNumber"
    }

    /** The OBR refused the invoice [invoice]; its message is kept with the status of the invoice. */
    data class InvoiceRejected(val invoice: InvoiceId) : AuditEvent {
        override val actor: UserId? get() = null
        override val action get() = "INVOICE_REJECTED"
        override val details get() = "invoice=${invoice.value}"
    }

    /** The user asked for the cancellation of [invoice], approved by [approver] when not null. */
    data class InvoiceCancellationRequested(
        override val actor: UserId,
        val invoice: InvoiceId,
        val approver: UserId?,
    ) : AuditEvent {
        override val action get() = "INVOICE_CANCELLATION_REQUESTED"
        override val details get() = "invoice=${invoice.value}" + approver?.let { ";approver=${it.value}" }.orEmpty()
    }

    /** The user started a sale that replaces [invoice], approved by [approver] when not null. */
    data class InvoiceReplacementStarted(
        override val actor: UserId,
        val invoice: InvoiceId,
        val approver: UserId?,
    ) : AuditEvent {
        override val action get() = "INVOICE_REPLACEMENT_STARTED"
        override val details get() = "invoice=${invoice.value}" + approver?.let { ";approver=${it.value}" }.orEmpty()
    }

    /** The OBR cancelled [invoice], by `cancelInvoice/` or by accepting the invoice that replaces it. */
    data class InvoiceCancelled(val invoice: InvoiceId) : AuditEvent {
        override val actor: UserId? get() = null
        override val action get() = "INVOICE_CANCELLED"
        override val details get() = "invoice=${invoice.value}"
    }

    /** The OBR refused to cancel [invoice], with its [message]. */
    data class InvoiceCancellationRefused(val invoice: InvoiceId, val message: String) : AuditEvent {
        override val actor: UserId? get() = null
        override val action get() = "INVOICE_CANCELLATION_REFUSED"
        override val details get() = "invoice=${invoice.value}"
    }

    /** The OBR rejected [invoice] before its cancellation left: the cancellation was dropped. */
    data class InvoiceCancellationAbandoned(val invoice: InvoiceId) : AuditEvent {
        override val actor: UserId? get() = null
        override val action get() = "INVOICE_CANCELLATION_ABANDONED"
        override val details get() = "invoice=${invoice.value}"
    }

    /**
     * The user issued the credit note [invoice], numbered [number], on the invoice [credited],
     * approved by [approver] when not null.
     */
    data class CreditNoteIssued(
        override val actor: UserId,
        val invoice: InvoiceId,
        val number: String,
        val credited: InvoiceId,
        val approver: UserId?,
    ) : AuditEvent {
        override val action get() = "CREDIT_NOTE_ISSUED"
        override val details
            get() = "invoice=${invoice.value};number=$number;credited=${credited.value}" +
                approver?.let { ";approver=${it.value}" }.orEmpty()
    }

    /** The refused invoice [invoice] was put back in the send queue with the tax profile [profileVersion]. */
    data class InvoiceResent(override val actor: UserId, val invoice: InvoiceId, val profileVersion: Int) : AuditEvent {
        override val action get() = "INVOICE_RESENT"
        override val details get() = "invoice=${invoice.value};profile=$profileVersion"
    }

    /** A PDF of the invoice [invoice] was created, to open or to share. */
    data class InvoicePdfCreated(override val actor: UserId, val invoice: InvoiceId) : AuditEvent {
        override val action get() = "INVOICE_PDF_CREATED"
        override val details get() = "invoice=${invoice.value}"
    }

    /** The ticket of the invoice [invoice] was printed. */
    data class InvoicePrinted(override val actor: UserId, val invoice: InvoiceId) : AuditEvent {
        override val action get() = "INVOICE_PRINTED"
        override val details get() = "invoice=${invoice.value}"
    }

    /** The invoice [invoice] was issued with the number [number]. */
    data class InvoiceIssued(override val actor: UserId, val invoice: InvoiceId, val number: String) : AuditEvent {
        override val action get() = "INVOICE_ISSUED"
        override val details get() = "invoice=${invoice.value};number=$number"
    }
}
