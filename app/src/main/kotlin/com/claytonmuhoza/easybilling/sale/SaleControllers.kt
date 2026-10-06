package com.claytonmuhoza.easybilling.sale

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountApproval
import com.claytonmuhoza.easybilling.domain.invoicing.rateOf
import com.claytonmuhoza.easybilling.identity.ApprovalError
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.Approver
import com.claytonmuhoza.easybilling.domain.invoicing.PricedLine
import com.claytonmuhoza.easybilling.domain.invoicing.SaleDraftRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineCheck
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineForm
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.ui.storageCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * The state of the cash register, shared by the parts that drive it. Changes of the sale run one
 * at a time, and the sale is read again after each one.
 *
 * @param scope where the changes run.
 * @param useCases what the cash register does.
 * @param items where the items are kept.
 * @param drafts where the sales on hold are counted.
 * @param actor the user logged in, or null.
 */
internal class SaleStateHolder(
    val scope: CoroutineScope,
    val useCases: SaleUseCases,
    val items: ItemRepository,
    private val drafts: SaleDraftRepository,
    val actor: () -> UserId?,
) {
    /** The state to show. */
    val state = MutableStateFlow<SaleUiState>(SaleUiState.Loading)

    /** The state when the sale is shown, or null. */
    val ready: SaleUiState.Ready? get() = state.value as? SaleUiState.Ready

    /** @param transform what changes on screen at once, without touching the stored sale. */
    fun show(transform: (SaleUiState.Ready) -> SaleUiState.Ready) {
        ready?.let { state.value = transform(it) }
    }

    /** @param block a change of the stored sale; it is ignored while another one runs. */
    fun change(block: suspend (SaleUiState.Ready) -> SaleUiState.Ready) {
        val shown = ready?.takeIf { !it.busy } ?: return
        val idle = shown.copy(failed = false, refusal = null)
        state.value = idle.copy(busy = true)
        scope.launch {
            state.value = storageCall { refreshed(block(idle)) }.getOrElse { idle.copy(failed = true) }
        }
    }

    /** @return [shown] with the sale, the items found and the sales on hold read again. */
    suspend fun refreshed(shown: SaleUiState.Ready): SaleUiState.Ready = shown.copy(
        sale = useCases.price(useCases.edit.current()),
        results = found(shown.query),
        heldCount = drafts.held().size,
        busy = false,
    )

    /** @return the sale being typed, shown for the first time. */
    suspend fun started(): SaleUiState.Ready = refreshed(SaleUiState.Ready(useCases.price(useCases.edit.current())))

    /** @return the active items matching [query], [SaleViewModel.LIMIT] at most. */
    suspend fun found(query: String) = items.search(query, archived = false, limit = SaleViewModel.LIMIT)
}

/** The catalogue of the cash register: the search and the items added with one touch. */
class SaleCatalogue internal constructor(private val holder: SaleStateHolder) {

    private var search: Job? = null

    /** @param query the new content of the search field; the items follow at once. */
    fun onQueryChange(query: String) {
        holder.show { it.copy(query = query) }
        search?.cancel()
        search = holder.scope.launch {
            val found = storageCall { holder.found(query) }.getOrNull() ?: return@launch
            holder.show { it.copy(results = found) }
        }
    }

    /**
     * Adds the item the search designates: the one with that barcode, or the only item found. A
     * Bluetooth reader types the barcode then presses "enter", which lands here.
     */
    fun onSearchSubmit() = holder.change { shown ->
        val scanned = holder.items.findByBarcode(shown.query.trim())?.takeIf { !it.archived }
        val item = scanned ?: shown.results.singleOrNull()
        if (item == null) {
            shown
        } else {
            holder.useCases.edit.addItem(item.id)
            shown.copy(query = "")
        }
    }

    /** @param itemId the item to add: a new line, or one more on its line. */
    fun addItem(itemId: ItemId) = holder.change { shown ->
        holder.useCases.edit.addItem(itemId)
        shown
    }

    /** @param itemId the item whose line loses one unit; the line goes when nothing is left. */
    fun removeOne(itemId: ItemId) = holder.change { shown ->
        val line = shown.sale.draft.lines.firstOrNull { it.itemId == itemId }
        val left = line?.let { it.quantity.value - BigDecimal.ONE }
        when {
            line == null || left == null -> Unit
            left.signum() > 0 -> {
                val less = SaleLineCheck.Valid(Quantity.of(left), line.discount, line.freeAmounts)
                holder.useCases.edit.setLine(itemId, less)
            }
            else -> holder.useCases.edit.removeLine(itemId)
        }
        shown.copy(editor = shown.editor?.takeIf { it.itemId != itemId })
    }
}

/** The line being modified: its quantity, its discount (UC-C03) and the taxes typed on it. */
class SaleLineEditing internal constructor(private val holder: SaleStateHolder) {

    /** @param itemId the item whose line is opened to be modified. */
    fun open(itemId: ItemId) = holder.show { shown ->
        val line = shown.sale.draft.lines.firstOrNull { it.itemId == itemId }
        if (line == null) shown else shown.copy(editor = LineEditor(itemId, SaleLineForm.from(line)))
    }

    /** Closes the line being modified without changing it. */
    fun close() = holder.show { it.copy(editor = null) }

    /** @param change the change of one field; the problems are cleared until the next attempt. */
    fun onFormChange(change: SaleLineForm.() -> SaleLineForm) = holder.show { shown ->
        shown.copy(editor = shown.editor?.let { it.copy(form = it.form.change(), problems = emptySet()) })
    }

    /**
     * Stores what was typed for the line being modified, or shows what is wrong. A discount beyond
     * the cap of the user waits for the approval of a manager instead (UC-C03).
     */
    fun apply() = holder.change { shown ->
        val editor = shown.editor
        when (val check = editor?.form?.check()) {
            null -> shown
            is SaleLineCheck.Invalid -> shown.copy(editor = editor.copy(problems = check.problems))
            is SaleLineCheck.Valid -> approvalNeeded(shown, editor.itemId, check)?.let { shown.copy(approval = it) }
                ?: shown.copy(editor = null).also { holder.useCases.edit.setLine(editor.itemId, check) }
        }
    }

    /** @return the approval [line] needs before it is stored, or null when it needs none. */
    private suspend fun approvalNeeded(
        shown: SaleUiState.Ready,
        itemId: ItemId,
        line: SaleLineCheck.Valid,
    ): LineApproval? {
        val actor = holder.actor()
        val priced = shown.sale.lines.firstOrNull { it.item.id == itemId }
        return if (actor == null || priced == null) null else approvalNeeded(actor, priced, line)
    }

    /** @return the approval [line] of [priced] needs before [actor] stores it, or null when it needs none. */
    private suspend fun approvalNeeded(actor: UserId, priced: PricedLine, line: SaleLineCheck.Valid): LineApproval? {
        val limits = holder.useCases.discountLimits
        val discount = line.discount
        val listPrice = priced.item.details.price
        // An approval of the same discount is kept by the line: it is not asked again.
        val approved = priced.line.approval?.discount == discount
        if (discount == null || approved || !limits.needsApproval(actor, discount, listPrice)) return null
        return LineApproval(
            itemId = priced.item.id,
            itemCode = priced.item.code,
            line = line,
            rate = discount.rateOf(listPrice),
            cap = limits.capOf(actor) ?: BigDecimal.ZERO,
            dialog = ApprovalUiState(holder.useCases.authorize.approvers(actor).map { Approver(it.id.value, it.name) }),
        )
    }

    /** @param itemId the item whose line is taken out of the sale. */
    fun remove(itemId: ItemId) = holder.change { shown ->
        holder.useCases.edit.removeLine(itemId)
        shown.copy(editor = null)
    }
}

/**
 * The approval of a discount beyond the cap of the user by a manager or an administrator, with
 * their own PIN (UC-H04). The session does not change.
 */
class SaleDiscountApproval internal constructor(private val holder: SaleStateHolder) {

    /** @param approverId the approver picked; the PIN typed so far is cleared. */
    fun choose(approverId: Long) = update { copy(chosen = approverId, pin = "", error = null) }

    /** @param pin the PIN after a key of the pad was pressed. */
    fun onPinChange(pin: String) = update { copy(pin = pin, error = null) }

    /** Gives the discount up: the line stays as it was, and its editor stays open. */
    fun cancel() = holder.show { it.copy(approval = null) }

    /** Checks the PIN of the approver; once approved, the line is stored with its approval. */
    fun submit() = holder.change { shown ->
        val request = shown.approval
        val approver = request?.dialog?.chosen
        val actor = holder.actor()
        if (request == null || approver == null || actor == null) {
            shown
        } else {
            val action = SensitiveAction.LineDiscount(request.itemCode, request.rate)
            val result = holder.useCases.authorize(actor, UserId(approver), request.dialog.pin, action)
            shown.after(request, result)
        }
    }

    private suspend fun SaleUiState.Ready.after(
        request: LineApproval,
        result: AuthorizeSensitiveAction.Result,
    ): SaleUiState.Ready {
        val dialog = request.dialog.copy(pin = "")
        val error = when (result) {
            is AuthorizeSensitiveAction.Result.Authorized -> {
                val discount = checkNotNull(request.line.discount) { "Only a discount is approved" }
                holder.useCases.edit.setLine(request.itemId, request.line, DiscountApproval(result.approver, discount))
                return copy(approval = null, editor = null)
            }
            is AuthorizeSensitiveAction.Result.WrongPin -> ApprovalError.WrongPin(result.remainingAttempts)
            is AuthorizeSensitiveAction.Result.Locked -> ApprovalError.Locked
            AuthorizeSensitiveAction.Result.NotAllowed -> ApprovalError.NotAllowed
        }
        return copy(approval = request.copy(dialog = dialog.copy(error = error)))
    }

    private fun update(change: ApprovalUiState.() -> ApprovalUiState) = holder.show { shown ->
        shown.copy(approval = shown.approval?.let { it.copy(dialog = it.dialog.change()) })
    }
}
