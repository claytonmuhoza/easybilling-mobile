package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.stock.CloseInventory
import com.claytonmuhoza.easybilling.domain.stock.CountInventory
import com.claytonmuhoza.easybilling.domain.stock.CountProblem
import com.claytonmuhoza.easybilling.domain.stock.InventoryLine
import com.claytonmuhoza.easybilling.domain.stock.ReadInventory
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.approvalFor
import com.claytonmuhoza.easybilling.identity.refusalOf
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the inventory uses, grouped so the view model receives it as one.
 *
 * @property read reads the inventory in progress.
 * @property count keeps what is counted, and gives the inventory up.
 * @property close closes the inventory.
 * @property authorize approves the closing with the PIN of a manager.
 */
class InventoryUseCases @Inject constructor(
    val read: ReadInventory,
    val count: CountInventory,
    val close: CloseInventory,
    val authorize: AuthorizeSensitiveAction,
)

/**
 * An item of the inventory as the screen shows it.
 *
 * @property line the item, its stock and what is kept of its count.
 * @property counted the quantity as typed, kept even when it breaks a rule.
 * @property cost the unit purchase price as typed.
 * @property problems the rules what is typed breaks.
 */
data class InventoryRow(
    val line: InventoryLine,
    val counted: String,
    val cost: String,
    val problems: Set<CountProblem> = emptySet(),
)

/** Why the inventory was not closed at the last attempt. */
enum class InventoryRefusal {
    /** Nothing is counted. */
    NOTHING_COUNTED,

    /** The note is too long. */
    NOTE_TOO_LONG,

    /** An item found in excess has no purchase price. */
    MISSING_COST,

    /** The closing could not be written. */
    FAILED,
}

/** State of the inventory. */
sealed interface InventoryUiState {
    /** The inventory is being read. */
    data object Loading : InventoryUiState

    /** The inventory could not be read. */
    data object Failed : InventoryUiState

    /**
     * The inventory is shown.
     *
     * @property query what the user typed in the search field.
     * @property rows the items kept in stock that match.
     * @property note what the user adds to the description of the adjustments.
     * @property needsApproval whether the user closes the inventory with the PIN of a manager.
     * @property refusal why the inventory was not closed at the last attempt, or null.
     * @property closedWith how many adjustments the inventory just closed made, or null.
     * @property approval the choice of the approver and their PIN, or null.
     * @property busy true while the inventory is closed or given up.
     */
    data class Ready(
        val query: String = "",
        val rows: List<InventoryRow> = emptyList(),
        val note: String = "",
        val needsApproval: Boolean = false,
        val refusal: InventoryRefusal? = null,
        val closedWith: Int? = null,
        val approval: ApprovalUiState? = null,
        val busy: Boolean = false,
    ) : InventoryUiState {
        /** How many of the items shown are counted. */
        val countedRows: Int get() = rows.count { it.line.count != null }

        /** How many of the items shown are counted more or less than their stock. */
        val gaps: Int get() = rows.count { (it.line.gap?.signum() ?: 0) != 0 }
    }
}

/**
 * Drives the inventory (UC-F08): each count is kept as it is typed, so the inventory is taken up
 * again later; closing it adjusts the stock, with the PIN of a manager for a user who is not one.
 */
@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val useCases: InventoryUseCases,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    /** What is typed for each item, kept apart from what is stored: a text that breaks a rule stays. */
    private val typed = mutableMapOf<ItemId, Pair<String, String>>()
    private val problems = mutableMapOf<ItemId, Set<CountProblem>>()

    private val mutableState = MutableStateFlow<InventoryUiState>(InventoryUiState.Loading)

    /** The state to show. */
    val state: StateFlow<InventoryUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the inventory. Also used to retry. */
    fun load() {
        viewModelScope.launch { refresh { this } }
    }

    /** @param query the new content of the search field. */
    fun onQueryChange(query: String) {
        viewModelScope.launch { refresh { copy(query = query) } }
    }

    /** @param note the new content of the note. */
    fun onNoteChange(note: String) = update { copy(note = note, refusal = null) }

    /**
     * Keeps what is typed for an item.
     *
     * @param itemId the item counted.
     * @param counted the quantity as typed.
     * @param cost the unit purchase price as typed.
     */
    fun onCount(itemId: Long, counted: String, cost: String) {
        val actor = sessions.current.value?.userId ?: return
        val id = ItemId(itemId)
        typed[id] = counted to cost
        // The field shows what is typed without waiting for the database.
        update { copy(rows = rows.map { if (it.line.item.id == id) it.copy(counted = counted, cost = cost) else it }) }
        viewModelScope.launch {
            val result = storageCall { useCases.count(id, counted, cost, actor) }.getOrNull()
            problems[id] = (result as? CountInventory.Result.Invalid)?.problems.orEmpty()
            refresh { copy(refusal = null, closedWith = null) }
        }
    }

    /** Closes the inventory; a user who is not a manager is first asked for the PIN of one. */
    fun close() {
        val ready = (mutableState.value as? InventoryUiState.Ready)?.takeUnless { it.busy } ?: return
        val actor = sessions.current.value?.userId ?: return
        mutableState.value = ready.copy(busy = true, refusal = null, closedWith = null)
        viewModelScope.launch {
            val idle = ready.copy(busy = false)
            val checked = storageCall { useCases.close.problemOf(ready.note) }
            val problem = checked.getOrNull()
            when {
                checked.isFailure -> mutableState.value = idle.copy(refusal = InventoryRefusal.FAILED)
                problem != null -> mutableState.value = idle.copy(refusal = problem.refusal())
                ready.needsApproval ->
                    mutableState.value = idle.copy(approval = useCases.authorize.approvalFor(actor))
                else -> closed(idle, actor, approver = null)
            }
        }
    }

    /**
     * @param change what the user changed of the approval being asked for: the approver, the PIN, or
     * null when the approval is given up; the counts stay.
     */
    fun onApprovalChange(change: ApprovalUiState.() -> ApprovalUiState?) =
        update { copy(approval = approval?.change()) }

    /** Checks the PIN of the approver; once approved, the inventory is closed with that approval. */
    fun approve() {
        val ready = (mutableState.value as? InventoryUiState.Ready)?.takeUnless { it.busy }
        val actor = sessions.current.value?.userId
        val chosen = ready?.approval?.chosen
        if (ready == null || actor == null || chosen == null) return
        val approval = checkNotNull(ready.approval)
        mutableState.value = ready.copy(busy = true)
        viewModelScope.launch {
            val action = SensitiveAction.InventoryClosing(ready.countedRows)
            val error = useCases.authorize.refusalOf(actor, UserId(chosen), approval.pin, action)
            val idle = ready.copy(busy = false)
            if (error == null) {
                closed(idle.copy(approval = null), actor, UserId(chosen))
            } else {
                mutableState.value = idle.copy(approval = approval.copy(pin = "", error = error))
            }
        }
    }

    /** Gives the inventory up: nothing that was counted is kept. */
    fun discard() {
        val actor = sessions.current.value?.userId ?: return
        viewModelScope.launch {
            storageCall { useCases.count.discard(actor) }
            typed.clear()
            problems.clear()
            refresh { copy(note = "", refusal = null, closedWith = null) }
        }
    }

    private suspend fun closed(ready: InventoryUiState.Ready, actor: UserId, approver: UserId?) {
        val result = storageCall { useCases.close(ready.note, actor, approver) }.getOrNull()
        if (result is CloseInventory.Result.Closed) {
            typed.clear()
            problems.clear()
            transmission.kick()
            mutableState.value = ready
            refresh { copy(note = "", closedWith = result.adjustments.size) }
        } else {
            mutableState.value = ready.copy(refusal = result?.refusal() ?: InventoryRefusal.FAILED)
        }
    }

    /** Reads the items again, keeping what is typed, then applies [change] to the state shown. */
    private suspend fun refresh(change: InventoryUiState.Ready.() -> InventoryUiState.Ready) {
        val shown = (mutableState.value as? InventoryUiState.Ready ?: InventoryUiState.Ready()).change()
        val manages = sessions.current.value?.can(Permission.MANAGE_STOCK) == true
        mutableState.value = storageCall {
            val rows = useCases.read(shown.query, LIMIT).map { line ->
                val text = typed[line.item.id]
                InventoryRow(
                    line = line,
                    counted = text?.first ?: line.count?.counted?.toPlainString().orEmpty(),
                    cost = text?.second ?: line.count?.unitCost?.toPlainString().orEmpty(),
                    problems = problems[line.item.id].orEmpty(),
                )
            }
            shown.copy(rows = rows, needsApproval = !manages)
        }.getOrElse { InventoryUiState.Failed }
    }

    private fun update(change: InventoryUiState.Ready.() -> InventoryUiState.Ready) {
        val ready = mutableState.value as? InventoryUiState.Ready ?: return
        mutableState.value = ready.change()
    }

    /** Limits of the inventory. */
    companion object {
        /** Most items shown at once: beyond, the user refines the search. */
        const val LIMIT = 100
    }
}

/** @return why the closing changed nothing, as the screen says it. */
private fun CloseInventory.Result.refusal(): InventoryRefusal? = when (this) {
    is CloseInventory.Result.Closed -> null
    CloseInventory.Result.NothingCounted -> InventoryRefusal.NOTHING_COUNTED
    CloseInventory.Result.NoteTooLong -> InventoryRefusal.NOTE_TOO_LONG
    is CloseInventory.Result.MissingCost -> InventoryRefusal.MISSING_COST
}
