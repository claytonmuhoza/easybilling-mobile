package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How far a cashier may lower a price alone (UC-C03, D50).
 *
 * @property cashierCap the highest discount a cashier gives without approval, as a share of the
 * list price in percent; null for no cap, which is the default.
 */
data class DiscountSettings(val cashierCap: BigDecimal? = null)

/** Storage of the [DiscountSettings]. */
interface DiscountSettingsRepository {
    /** @return the stored settings, or the defaults when nothing was saved. */
    suspend fun read(): DiscountSettings

    /** @param settings the settings that replace the stored ones. */
    suspend fun write(settings: DiscountSettings)
}

/**
 * The approval of a discount beyond the cap of the user who typed it (UC-H04). It holds only for
 * that discount: a line whose discount changes loses it.
 *
 * @property approver the manager or administrator who approved it with their PIN.
 * @property discount the discount approved.
 */
data class DiscountApproval(val approver: UserId, val discount: Discount)

/** Decimals of a rate, as for any discount typed (see [SaleLineForm.MAX_DECIMALS]). */
private const val RATE_DECIMALS = 2

/** A whole price, in percent. */
private val HUNDRED = BigDecimal(100)

/**
 * @param listPrice the unit price of the item before the discount.
 * @return the share of [listPrice] this discount takes off, in percent, rounded up to the
 * hundredth: a discount just above a cap is never taken for one within it.
 */
fun Discount.rateOf(listPrice: Money): BigDecimal = when (this) {
    is Discount.Percent -> rate
    is Discount.PerUnit -> (amount * HUNDRED).divide(listPrice.amount, RATE_DECIMALS, RoundingMode.CEILING)
}

/**
 * Tells whether a discount needs the approval of a manager. A user allowed to approve a sensitive
 * action has no cap, whatever their other roles; every other user has the cap of the cashier.
 */
class DiscountLimits(
    private val users: UserRepository,
    private val settings: DiscountSettingsRepository,
    private val policy: PermissionPolicy,
) {
    /**
     * @param userId the user who gives the discount.
     * @return their cap in percent, or null when they have none.
     */
    suspend fun capOf(userId: UserId): BigDecimal? {
        val user = users.find(userId)
        return when {
            user == null || policy.allows(user.roles, Permission.AUTHORIZE_SENSITIVE_ACTION) -> null
            else -> settings.read().cashierCap
        }
    }

    /**
     * @param userId the user who gives the discount.
     * @param discount the discount of a line, or null.
     * @param listPrice the unit price of the item before the discount.
     * @return true if [discount] goes beyond the cap of [userId].
     */
    suspend fun needsApproval(userId: UserId, discount: Discount?, listPrice: Money): Boolean {
        val cap = discount?.let { capOf(userId) } ?: return false
        return discount.rateOf(listPrice) > cap
    }

    /**
     * @param userId the user who issues [sale].
     * @param sale the sale about to be issued.
     * @return the lines whose discount goes beyond the cap of [userId] without an approval of it.
     */
    suspend fun unapproved(userId: UserId, sale: PricedSale): List<PricedLine> = sale.lines.filter { priced ->
        val line = priced.line
        needsApproval(userId, line.discount, priced.item.details.price) && line.approval?.discount != line.discount
    }
}

/** Saves the cap of the discount a cashier gives alone. Only who manages the users sets it. */
class SaveDiscountSettings(
    private val access: AccessControl,
    private val settings: DiscountSettingsRepository,
    private val auditLog: AuditLog,
) {
    /** Outcome of a save. */
    enum class Result {
        /** The cap is saved. */
        Saved,

        /** The cap is not between 0 and 100 excluded, with 2 decimals at most; nothing was saved. */
        OutOfRange,
    }

    /**
     * @param cashierCap the cap in percent, or null for none.
     * @param actor the user who sets it.
     * @return whether it was saved.
     * @throws com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException if [actor] is not allowed to.
     */
    suspend operator fun invoke(cashierCap: BigDecimal?, actor: UserId): Result {
        access.require(actor, Permission.MANAGE_USERS)
        val valid = cashierCap == null ||
            cashierCap.signum() >= 0 && cashierCap < HUNDRED && cashierCap.stripTrailingZeros().scale() <= RATE_DECIMALS
        if (!valid) return Result.OutOfRange
        settings.write(DiscountSettings(cashierCap))
        auditLog.record(AuditEvent.DiscountCapChanged(actor, cashierCap))
        return Result.Saved
    }
}
