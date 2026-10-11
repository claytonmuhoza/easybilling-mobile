package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import java.math.BigDecimal
import java.time.Instant

/** An action a user may only do with the approval of a manager or an administrator (UC-H04). */
sealed interface SensitiveAction {
    /** What the audit log says of the action, as `key=value` pairs separated by `;`. */
    val details: String

    /**
     * A discount on a line beyond the cap of the user (UC-C03).
     *
     * @property itemCode the code of the item of the line.
     * @property rate the discount, as a share of the list price in percent.
     */
    data class LineDiscount(val itemCode: String, val rate: BigDecimal) : SensitiveAction {
        override val details get() =
            "action=LINE_DISCOUNT;item=$itemCode;rate=${rate.stripTrailingZeros().toPlainString()}"
    }

    /**
     * The cancellation of an invoice, or its replacement (UC-E01, E02).
     *
     * @property invoiceNumber the number of the invoice.
     * @property replace true when the invoice is replaced, false when it is only cancelled.
     */
    data class InvoiceCancellation(val invoiceNumber: String, val replace: Boolean) : SensitiveAction {
        override val details get() = "action=INVOICE_CANCELLATION;invoice=$invoiceNumber;replace=$replace"
    }

    /**
     * A credit note on an invoice (UC-E03).
     *
     * @property invoiceNumber the number of the invoice that is credited.
     */
    data class CreditNote(val invoiceNumber: String) : SensitiveAction {
        override val details get() = "action=CREDIT_NOTE;invoice=$invoiceNumber"
    }

    /**
     * An exit of stock that is not a sale (UC-F07).
     *
     * @property itemCode the code of the item that leaves the stock.
     * @property type the code of the type of the exit.
     * @property quantity the quantity, as typed.
     */
    data class StockExit(val itemCode: String, val type: String, val quantity: String) : SensitiveAction {
        override val details get() = "action=STOCK_EXIT;item=$itemCode;type=$type;quantity=$quantity"
    }
}

/**
 * Approves a sensitive action with the PIN of another user allowed to (UC-H04). The session does
 * not change; the wrong PINs count towards the lock of the approver (D19).
 */
class AuthorizeSensitiveAction(
    private val users: UserRepository,
    private val guard: SecretGuard,
    private val policy: PermissionPolicy,
    private val auditLog: AuditLog,
) {
    /** Outcome of an approval. */
    sealed interface Result {
        /** [approver] approved the action. */
        data class Authorized(val approver: UserId) : Result

        /** The PIN is wrong; [remainingAttempts] are left before the account of the approver locks. */
        data class WrongPin(val remainingAttempts: Int) : Result

        /** The account of the approver refuses every attempt until [until]. */
        data class Locked(val until: Instant) : Result

        /**
         * The approver may not approve: unknown, disabled, still on a temporary PIN, without the
         * right, or the requester themselves. No PIN was looked at.
         */
        data object NotAllowed : Result
    }

    /**
     * @param requester the user who asks for the action.
     * @return who may approve it: the active accounts allowed to, with their own PIN, oldest first.
     */
    suspend fun approvers(requester: UserId): List<User> = users.findAll().filter { it.canApprove(requester) }

    /**
     * @param requester the user who asks for the action.
     * @param approver the user who approves it.
     * @param pin the PIN of [approver] as typed.
     * @param action what is approved.
     * @return whether the action is approved. Both outcomes of a typed PIN are audited.
     */
    suspend operator fun invoke(requester: UserId, approver: UserId, pin: String, action: SensitiveAction): Result {
        val user = users.find(approver)?.takeIf { it.canApprove(requester) } ?: return Result.NotAllowed
        val check = guard.check(user, pin, user.credentials.pinHash, AuditEvent.PinRejected(user.id))
        val result = when (check) {
            is SecretCheck.Accepted -> Result.Authorized(user.id)
            is SecretCheck.Rejected -> Result.WrongPin(check.remainingAttempts)
            is SecretCheck.Locked -> Result.Locked(check.until)
        }
        auditLog.record(
            if (result is Result.Authorized) {
                AuditEvent.SensitiveActionAuthorized(approver, requester, action)
            } else {
                AuditEvent.SensitiveActionRefused(requester, approver, action)
            },
        )
        return result
    }

    private fun User.canApprove(requester: UserId): Boolean =
        id != requester && active && !pinTemporary && policy.allows(roles, Permission.AUTHORIZE_SENSITIVE_ACTION)
}
