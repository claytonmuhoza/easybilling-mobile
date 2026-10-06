package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.math.BigDecimal

/**
 * Changes the sale being typed and the sales on hold (UC-C01 steps 1 and 3, UC-C02, UC-C03). Every
 * change is stored at once, so the sale survives when the application is closed.
 *
 * @param drafts where the sales that are not issued are kept.
 * @param time source of the time of each change.
 * @param transactions makes each change a single write.
 */
class EditSale(
    private val drafts: SaleDraftRepository,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    /** @return the sale being typed; an empty one is started when there is none. */
    suspend fun current(): SaleDraft = transactions.run { drafts.current() ?: drafts.create(time.now()) }

    /**
     * Adds one unit of an item: a new line, or one more on the line the item already has.
     *
     * @param itemId the item.
     * @return the sale once changed.
     */
    suspend fun addItem(itemId: ItemId): SaleDraft = change { draft ->
        val existing = draft.lines.firstOrNull { it.itemId == itemId }
        if (existing == null) {
            draft.copy(lines = draft.lines + DraftLine(itemId, Quantity.of(BigDecimal.ONE)))
        } else {
            val more = existing.copy(quantity = Quantity.of(existing.quantity.value + BigDecimal.ONE))
            draft.copy(lines = draft.lines.map { if (it.itemId == itemId) more else it })
        }
    }

    /**
     * @param itemId the item of the line.
     * @param line the quantity, the discount and the tax amounts that replace the ones of the line.
     * @param approval the approval of the discount of [line] by a manager, or null. The approval
     * the line already has is kept while its discount stays the same; an approval of another
     * discount is not kept.
     * @return the sale once changed.
     */
    suspend fun setLine(itemId: ItemId, line: SaleLineCheck.Valid, approval: DiscountApproval? = null): SaleDraft =
        change { draft ->
            val previous = draft.lines.firstOrNull { it.itemId == itemId }?.approval
            val kept = (approval ?: previous)?.takeIf { line.discount != null && it.discount == line.discount }
            val changed = DraftLine(itemId, line.quantity, line.discount, line.freeAmounts, kept)
            draft.copy(lines = draft.lines.map { if (it.itemId == itemId) changed else it })
        }

    /**
     * @param itemId the item of the line to take out.
     * @return the sale once changed.
     */
    suspend fun removeLine(itemId: ItemId): SaleDraft =
        change { draft -> draft.copy(lines = draft.lines.filterNot { it.itemId == itemId }) }

    /**
     * @param customerId the customer, or null for the counter customer.
     * @return the sale once changed.
     */
    suspend fun setCustomer(customerId: CustomerId?): SaleDraft = change { it.copy(customerId = customerId) }

    /**
     * @param paymentType how the sale is paid.
     * @return the sale once changed.
     */
    suspend fun setPaymentType(paymentType: PaymentType): SaleDraft = change { it.copy(paymentType = paymentType) }

    /**
     * Puts the sale being typed on hold (UC-C02), so another one can be typed. An empty sale is
     * not kept.
     *
     * @return whether a sale was put on hold.
     */
    suspend fun hold(): Boolean = transactions.run {
        val draft = drafts.current()?.takeIf { it.lines.isNotEmpty() } ?: return@run false
        drafts.save(draft.copy(held = true, updatedAt = time.now()))
        true
    }

    /**
     * Takes a sale on hold back to the cash register. The sale being typed takes its place on
     * hold, unless it is empty.
     *
     * @param id the sale on hold.
     * @return whether the sale was found.
     */
    suspend fun resume(id: DraftId): Boolean = transactions.run {
        val resumed = drafts.find(id)?.takeIf { it.held } ?: return@run false
        val typed = drafts.current()
        when {
            typed == null -> Unit
            typed.lines.isEmpty() -> drafts.delete(typed.id)
            else -> drafts.save(typed.copy(held = true, updatedAt = time.now()))
        }
        drafts.save(resumed.copy(held = false, updatedAt = time.now()))
        true
    }

    /** @param id the sale to forget; nothing fiscal is lost, since it has no number. */
    suspend fun discard(id: DraftId) = transactions.run { drafts.delete(id) }

    private suspend fun change(change: (SaleDraft) -> SaleDraft): SaleDraft = transactions.run {
        val changed = change(current()).copy(updatedAt = time.now())
        drafts.save(changed)
        changed
    }
}
