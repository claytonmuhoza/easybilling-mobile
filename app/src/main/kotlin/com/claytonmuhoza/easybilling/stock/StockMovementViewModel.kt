package com.claytonmuhoza.easybilling.stock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.stock.MovementDirection
import com.claytonmuhoza.easybilling.domain.stock.MovementProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.RecordStockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementForm
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.domain.stock.StockRepository
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.approvalFor
import com.claytonmuhoza.easybilling.identity.refusalOf
import com.claytonmuhoza.easybilling.navigation.StockMovementDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * What the entry of a stock movement uses, grouped so the view model receives it as one.
 *
 * @property items the catalogue.
 * @property stock the movements.
 * @property record records a movement.
 * @property authorize approves an exit with the PIN of a manager.
 */
class StockMovementUseCases @Inject constructor(
    val items: ItemRepository,
    val stock: StockRepository,
    val record: RecordStockMovement,
    val authorize: AuthorizeSensitiveAction,
)

/** State of the entry of a stock movement. */
sealed interface StockMovementUiState {
    /** The item is being read. */
    data object Loading : StockMovementUiState

    /** The item could not be read. */
    data object Failed : StockMovementUiState

    /** The item cannot move, for [problem]. */
    data class Unavailable(val problem: StockProblem) : StockMovementUiState

    /**
     * The movement can be typed.
     *
     * @property item the item that moves.
     * @property available its stock.
     * @property exit true for an exit, false for an entry.
     * @property needsApproval the types the logged in user only records with the PIN of a manager.
     * @property type the type chosen, or null.
     * @property quantity the quantity typed.
     * @property unitCost the unit purchase price typed; only for an entry.
     * @property description the description typed.
     * @property problems the rules broken at the last attempt.
     * @property insufficient the stock an exit tried to take more than at the last attempt, or null.
     * @property approval the choice of the approver and their PIN, or null.
     * @property busy true while the movement is checked or written.
     * @property failed true when the last attempt could not be written.
     */
    data class Form(
        val item: Item,
        val available: BigDecimal,
        val exit: Boolean,
        val needsApproval: Set<MovementType> = emptySet(),
        val type: MovementType? = null,
        val quantity: String = "",
        val unitCost: String = "",
        val description: String = "",
        val problems: Set<MovementProblem> = emptySet(),
        val insufficient: BigDecimal? = null,
        val approval: ApprovalUiState? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : StockMovementUiState {
        /** The types offered, in the order of the OBR. */
        val types: List<MovementType>
            get() {
                val direction = if (exit) MovementDirection.EXIT else MovementDirection.ENTRY
                return MovementType.entries.filter { it in MovementType.RECORDED_BY_HAND && it.direction == direction }
            }

        /** @return what was typed, for the use case. */
        fun typed(): StockMovementForm = StockMovementForm(item.id, type, quantity, unitCost, description)
    }

    /** The movement is recorded. */
    data object Done : StockMovementUiState
}

/**
 * Drives the entry of a stock movement typed by a user (UC-F01, F02, F05, F07). An exit that is
 * not a sale is validated by a manager: a user who is not one asks for their PIN (UC-H04), once
 * what was typed breaks no rule.
 */
@HiltViewModel
class StockMovementViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val useCases: StockMovementUseCases,
    private val sessions: SessionManager,
    private val transmission: TransmissionRunner,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<StockMovementDestination>()
    private val id = ItemId(destination.itemId)

    private val mutableState = MutableStateFlow<StockMovementUiState>(StockMovementUiState.Loading)

    /** The state to show. */
    val state: StateFlow<StockMovementUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the item. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            val manages = sessions.current.value?.can(Permission.MANAGE_STOCK) == true
            mutableState.value = storageCall {
                val item = useCases.items.find(id)
                when {
                    item == null -> StockMovementUiState.Unavailable(StockProblem.ITEM_NOT_FOUND)
                    !item.details.stockTracked -> StockMovementUiState.Unavailable(StockProblem.ITEM_NOT_TRACKED)
                    item.archived -> StockMovementUiState.Unavailable(StockProblem.ITEM_ARCHIVED)
                    else -> StockMovementUiState.Form(
                        item = item,
                        available = useCases.stock.quantityOf(id),
                        exit = destination.exit,
                        needsApproval = if (manages) emptySet() else MovementType.VALIDATED_BY_MANAGER,
                    )
                }
            }.getOrElse { StockMovementUiState.Failed }
        }
    }

    /**
     * @param change what the user changed of the movement being typed. The refusal of the last
     * attempt is forgotten: it was about what was typed then.
     */
    fun onFormChange(change: StockMovementUiState.Form.() -> StockMovementUiState.Form) =
        update { change().copy(insufficient = null, failed = false) }

    /**
     * Records the movement; when it needs the validation of a manager the user cannot give, asks
     * for the approval of a manager first, once what was typed breaks no rule.
     */
    fun submit() {
        val form = (mutableState.value as? StockMovementUiState.Form)?.takeUnless { it.busy } ?: return
        val actor = sessions.current.value?.userId ?: return
        mutableState.value = form.copy(busy = true, failed = false)
        viewModelScope.launch {
            val idle = form.copy(busy = false)
            val checked = storageCall { useCases.record.problemOf(form.typed()) }
            val problem = checked.getOrNull()
            mutableState.value = when {
                checked.isFailure -> idle.copy(failed = true)
                problem != null -> idle.stoppedBy(problem)
                form.type in form.needsApproval -> idle.copy(approval = useCases.authorize.approvalFor(actor))
                else -> recorded(idle, actor, approver = null)
            }
        }
    }

    /** @param approverId the approver picked; the PIN typed so far is cleared. */
    fun choose(approverId: Long) = updateApproval { copy(chosen = approverId, pin = "", error = null) }

    /** @param pin the PIN of the approver after a key of the pad was pressed. */
    fun onPinChange(pin: String) = updateApproval { copy(pin = pin, error = null) }

    /** Gives the approval up: what was typed stays. */
    fun dismissApproval() = update { copy(approval = null) }

    /** Checks the PIN of the approver; once approved, the exit is recorded with that approval. */
    fun approve() {
        val form = (mutableState.value as? StockMovementUiState.Form)?.takeUnless { it.busy }
        val actor = sessions.current.value?.userId
        val asked = form?.askedApproval()
        if (form == null || actor == null || asked == null) return
        mutableState.value = form.copy(busy = true)
        viewModelScope.launch {
            val error = useCases.authorize.refusalOf(actor, asked.approver, asked.dialog.pin, asked.action)
            val idle = form.copy(busy = false)
            mutableState.value = if (error == null) {
                recorded(idle.copy(approval = null), actor, asked.approver)
            } else {
                idle.copy(approval = asked.dialog.copy(pin = "", error = error))
            }
        }
    }

    private suspend fun recorded(
        form: StockMovementUiState.Form,
        actor: UserId,
        approver: UserId?,
    ): StockMovementUiState = storageCall {
        when (val result = useCases.record(form.typed(), actor, approver)) {
            is RecordStockMovement.Result.Recorded -> StockMovementUiState.Done.also { transmission.kick() }
            else -> form.stoppedBy(result)
        }
    }.getOrElse { form.copy(failed = true) }

    private fun updateApproval(change: ApprovalUiState.() -> ApprovalUiState) =
        update { copy(approval = approval?.change()) }

    private fun update(change: StockMovementUiState.Form.() -> StockMovementUiState.Form) {
        val form = mutableState.value as? StockMovementUiState.Form ?: return
        mutableState.value = form.change()
    }
}

/**
 * The approval of an exit, once an approver is chosen.
 *
 * @property dialog the choice of the approver and their PIN.
 * @property approver the approver chosen.
 * @property action what is approved.
 */
private class AskedApproval(val dialog: ApprovalUiState, val approver: UserId, val action: SensitiveAction)

/** @return the approval being asked for with its approver chosen, or null when there is none yet. */
private fun StockMovementUiState.Form.askedApproval(): AskedApproval? {
    val chosen = approval?.chosen
    return if (approval == null || type == null || chosen == null) {
        null
    } else {
        val action = SensitiveAction.StockExit(item.code, type.code, quantity.trim())
        AskedApproval(approval, UserId(chosen), action)
    }
}

/** @return the state showing why [result] recorded nothing. */
private fun StockMovementUiState.Form.stoppedBy(result: RecordStockMovement.Result): StockMovementUiState =
    when (result) {
        is RecordStockMovement.Result.Invalid -> copy(problems = result.problems)
        is RecordStockMovement.Result.InsufficientStock ->
            copy(insufficient = result.available, available = result.available)
        is RecordStockMovement.Result.Refused -> StockMovementUiState.Unavailable(result.problem)
        is RecordStockMovement.Result.Recorded -> StockMovementUiState.Done
    }
