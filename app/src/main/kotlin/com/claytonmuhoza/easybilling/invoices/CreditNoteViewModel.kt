package com.claytonmuhoza.easybilling.invoices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineForm
import com.claytonmuhoza.easybilling.domain.invoicing.CreditLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditNoteRules
import com.claytonmuhoza.easybilling.domain.invoicing.CreditProblem
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReason
import com.claytonmuhoza.easybilling.domain.invoicing.CreditReasonType
import com.claytonmuhoza.easybilling.domain.invoicing.CreditRequest
import com.claytonmuhoza.easybilling.domain.invoicing.Creditable
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.IssueCreditNote
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.ReasonProblem
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.identity.ApprovalUiState
import com.claytonmuhoza.easybilling.identity.approvalFor
import com.claytonmuhoza.easybilling.identity.refusalOf
import com.claytonmuhoza.easybilling.navigation.CreditNoteDestination
import com.claytonmuhoza.easybilling.transmission.TransmissionRunner
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * What a credit note uses, grouped so the view model receives it as one.
 *
 * @property rules tells what may be credited, and checks what is typed.
 * @property issue issues a credit note.
 * @property authorize approves the credit note with the PIN of a manager.
 */
class CreditNoteUseCases @Inject constructor(
    val rules: CreditNoteRules,
    val issue: IssueCreditNote,
    val authorize: AuthorizeSensitiveAction,
)

/**
 * A line of the invoice on the credit note being typed.
 *
 * @property itemId the item of the line.
 * @property designation what was sold.
 * @property sold the quantity invoiced.
 * @property remaining the quantity no credit note took yet.
 * @property includingTaxes whether the unit price includes the taxes.
 * @property selected whether the line is credited.
 * @property quantity the quantity to credit, as typed.
 * @property unitPrice the unit price to credit, as typed.
 * @property problems the rules a selected line breaks.
 * @property total the total credited by the line, or null while it is not selected or breaks a rule.
 */
data class CreditLineUi(
    val itemId: Long,
    val designation: String,
    val sold: BigDecimal,
    val remaining: BigDecimal,
    val includingTaxes: Boolean,
    val selected: Boolean,
    val quantity: String,
    val unitPrice: String,
    val problems: Set<CreditLineProblem> = emptySet(),
    val total: Money? = null,
) {
    /** Whether something is left to credit on the line. */
    val creditable: Boolean get() = remaining.signum() > 0
}

/** Why the credit note was not issued at the last attempt, the reason and the lines apart. */
enum class CreditIssueProblem {
    /** No line is selected. */
    NO_LINE,

    /** The configuration is not complete. */
    NOT_READY,

    /** The counter of the credit notes reached its last number. */
    NUMBERS_EXHAUSTED,

    /** The number does not fit in the identifier. */
    IDENTIFIER_TOO_LONG,

    /** The credit note could not be written. */
    FAILED,
}

/** State of the credit note being typed. */
sealed interface CreditNoteUiState {
    /** The invoice is being read. */
    data object Loading : CreditNoteUiState

    /** The invoice could not be read. */
    data object Failed : CreditNoteUiState

    /** The invoice cannot be credited now, for [problem]. */
    data class NotCreditable(val problem: CreditProblem) : CreditNoteUiState

    /**
     * The credit note can be typed.
     *
     * @property number the number of the invoice that is credited.
     * @property lines the lines of the invoice.
     * @property remainingAmount the most the credit note may be.
     * @property total the total of the lines selected that break no rule.
     * @property aboveCap whether [total] is more than [remainingAmount].
     * @property reasonType the kind of reason chosen, or null.
     * @property detail what the user adds to the reason.
     * @property reasonProblem the rule the reason broke at the last attempt, or null.
     * @property refusal why the invoice could not be credited at the last attempt, or null.
     * @property problem why nothing was issued at the last attempt, or null.
     * @property approval the approval of a manager that is waited for, or null.
     * @property busy true while the credit note is written.
     */
    data class Form(
        val number: String,
        val lines: List<CreditLineUi>,
        val remainingAmount: Money,
        val total: Money,
        val aboveCap: Boolean = false,
        val reasonType: CreditReasonType? = null,
        val detail: String = "",
        val reasonProblem: ReasonProblem? = null,
        val refusal: CreditProblem? = null,
        val problem: CreditIssueProblem? = null,
        val approval: ApprovalUiState? = null,
        val busy: Boolean = false,
    ) : CreditNoteUiState

    /** The credit note [creditNoteId] is issued. */
    data class Done(val creditNoteId: Long) : CreditNoteUiState
}

/**
 * Drives a credit note on an invoice (UC-E03): the lines, the quantities and the unit prices
 * credited, and the reason. A user who may not correct an invoice asks for the approval of a
 * manager (UC-H04).
 */
@HiltViewModel
class CreditNoteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    useCases: CreditNoteUseCases,
    sessions: SessionManager,
    transmission: TransmissionRunner,
) : ViewModel() {

    private val holder = CreditNoteStateHolder(
        id = InvoiceId(savedStateHandle.toRoute<CreditNoteDestination>().id),
        useCases = useCases,
        sessions = sessions,
        transmission = transmission,
        scope = viewModelScope,
    )

    /** The state to show. */
    val state: StateFlow<CreditNoteUiState> = holder.state.asStateFlow()

    /** The lines credited. */
    val lines = CreditNoteLines(holder)

    /** The approval of a manager. */
    val approval = CreditNoteApproval(holder)

    init {
        load()
    }

    /** Reads the invoice and what is left to credit on it. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            holder.state.value = storageCall { holder.read(typed = null) }.getOrElse { CreditNoteUiState.Failed }
        }
    }

    /** @param type the kind of reason chosen. */
    fun onReasonTypeChange(type: CreditReasonType) = holder.update { copy(reasonType = type, reasonProblem = null) }

    /** @param detail the new content of the detail of the reason. */
    fun onDetailChange(detail: String) = holder.update { copy(detail = detail, reasonProblem = null) }

    /**
     * Issues the credit note; asks for the approval of a manager first when the user may not correct
     * an invoice. Nothing is issued while a line or the reason breaks a rule.
     */
    fun submit() {
        val typed = holder.form?.takeUnless { it.busy } ?: return
        val session = holder.sessions.current.value ?: return
        val reasonProblem = CreditReason.problem(typed.reasonType, typed.detail)
        val selected = typed.lines.filter { it.selected }
        when {
            selected.isEmpty() -> holder.state.value = typed.copy(problem = CreditIssueProblem.NO_LINE)
            reasonProblem != null -> holder.state.value = typed.copy(reasonProblem = reasonProblem, problem = null)
            // The problems are already shown under the fields and above the button.
            typed.aboveCap || selected.any { it.problems.isNotEmpty() } -> Unit
            session.can(Permission.CORRECT_INVOICES) -> holder.issue(typed, approver = null)
            else -> viewModelScope.launch {
                holder.state.value = typed.copy(approval = holder.useCases.authorize.approvalFor(session.userId))
            }
        }
    }
}

/**
 * The state of a credit note being typed and what changes it, shared by the view model and its
 * controllers.
 *
 * @property id the invoice that is credited.
 * @property useCases what a credit note uses.
 * @property sessions the current session.
 * @property transmission sends the credit note once it is issued.
 * @property scope where the writes run.
 */
internal class CreditNoteStateHolder(
    val id: InvoiceId,
    val useCases: CreditNoteUseCases,
    val sessions: SessionManager,
    val transmission: TransmissionRunner,
    val scope: CoroutineScope,
) {
    /** The state to show. */
    val state = MutableStateFlow<CreditNoteUiState>(CreditNoteUiState.Loading)

    /** The invoice as it was last read: what is typed is checked against it. */
    private var source: Creditable? = null

    /** The form, when it is shown. */
    val form get() = state.value as? CreditNoteUiState.Form

    /** @param change what changes in the form, when it is shown. */
    fun update(change: CreditNoteUiState.Form.() -> CreditNoteUiState.Form) {
        val typed = form ?: return
        state.value = typed.change()
    }

    /**
     * @param typed the form whose reason and ticked lines are kept, or null for a new form.
     * @return the form on the invoice as it is now.
     */
    suspend fun read(typed: CreditNoteUiState.Form?): CreditNoteUiState {
        val problem = useCases.rules.problem(id)
        val read = useCases.rules.creditable(id)
        return when {
            problem != null -> CreditNoteUiState.NotCreditable(problem)
            read == null -> CreditNoteUiState.NotCreditable(CreditProblem.NOT_FOUND)
            else -> {
                source = read
                val lines = lines(read, typed)
                val start = typed ?: CreditNoteUiState.Form(
                    number = read.invoice.content.number,
                    lines = lines,
                    remainingAmount = read.remainingAmount,
                    total = Money.zero(read.remainingAmount.currency),
                )
                priced(start.copy(lines = lines, remainingAmount = read.remainingAmount))
            }
        }
    }

    private fun lines(read: Creditable, typed: CreditNoteUiState.Form?): List<CreditLineUi> {
        val kept = typed?.lines.orEmpty().associateBy { it.itemId }
        return read.lines.map { line ->
            val before = kept[line.line.itemId.value]?.takeIf { line.remaining.signum() > 0 }
            CreditLineUi(
                itemId = line.line.itemId.value,
                designation = line.line.designation,
                sold = line.line.quantity.value,
                remaining = line.remaining,
                includingTaxes = line.line.priceMode == PriceMode.INCLUDING_TAXES,
                selected = before?.selected ?: false,
                quantity = before?.quantity ?: line.remaining.toPlainString(),
                unitPrice = before?.unitPrice ?: line.netUnitPrice.toPlainString(),
            )
        }
    }

    /** @return [form] with the problems and the totals of its selected lines. */
    fun priced(form: CreditNoteUiState.Form): CreditNoteUiState.Form {
        val read = source ?: return form
        val credit = useCases.rules.price(read, form.lines.filter { it.selected }.map { it.toForm() })
        val checked = credit.lines.associateBy { it.form.itemId.value }
        return form.copy(
            lines = form.lines.map { line ->
                val priced = checked[line.itemId]
                line.copy(problems = priced?.problems.orEmpty(), total = priced?.line?.amounts?.total)
            },
            total = credit.total,
            aboveCap = credit.aboveCap,
        )
    }

    private fun CreditLineUi.toForm() = CreditLineForm(ItemId(itemId), quantity, unitPrice)

    /**
     * Issues the credit note of [typed].
     *
     * @param approver the manager who approved it with their PIN, or null when the user may.
     */
    fun issue(typed: CreditNoteUiState.Form, approver: UserId?) {
        val actor = sessions.current.value?.userId ?: return
        val idle = typed.copy(busy = false, refusal = null, problem = null)
        state.value = idle.copy(busy = true)
        val lines = typed.lines.filter { it.selected }.map { it.toForm() }
        val request = CreditRequest(id, lines, typed.reasonType, typed.detail)
        scope.launch {
            state.value = storageCall { issued(request, actor, approver, idle) }
                .getOrElse { idle.copy(problem = CreditIssueProblem.FAILED) }
        }
    }

    private suspend fun issued(
        request: CreditRequest,
        actor: UserId,
        approver: UserId?,
        idle: CreditNoteUiState.Form,
    ): CreditNoteUiState = when (val result = useCases.issue(request, actor, approver)) {
        // The credit note is stored: it is sent without making the user wait.
        is IssueCreditNote.Result.Issued ->
            CreditNoteUiState.Done(result.invoice.id.value).also { transmission.kick() }
        is IssueCreditNote.Result.Refused -> idle.copy(refusal = result.problem)
        is IssueCreditNote.Result.InvalidReason -> idle.copy(reasonProblem = result.problem)
        // Another credit note was issued meanwhile: what is left is read again.
        is IssueCreditNote.Result.Invalid -> read(idle)
        is IssueCreditNote.Result.NotReady -> idle.copy(problem = CreditIssueProblem.NOT_READY)
        IssueCreditNote.Result.NumbersExhausted -> idle.copy(problem = CreditIssueProblem.NUMBERS_EXHAUSTED)
        IssueCreditNote.Result.IdentifierTooLong -> idle.copy(problem = CreditIssueProblem.IDENTIFIER_TOO_LONG)
    }
}

/** The lines of a credit note being typed: which are credited, for which quantity and unit price. */
class CreditNoteLines internal constructor(private val holder: CreditNoteStateHolder) {

    /** @param itemId the line whose credit is turned on or off. */
    fun toggle(itemId: Long) = update(itemId) { copy(selected = !selected && creditable) }

    /** Selects every line that has something left to credit. */
    fun selectAll() = holder.update {
        holder.priced(copy(lines = lines.map { it.copy(selected = it.creditable) }, problem = null))
    }

    /**
     * @param itemId a line.
     * @param quantity the new content of its quantity field.
     */
    fun onQuantityChange(itemId: Long, quantity: String) = update(itemId) { copy(quantity = quantity) }

    /**
     * @param itemId a line.
     * @param unitPrice the new content of its unit price field.
     */
    fun onUnitPriceChange(itemId: Long, unitPrice: String) = update(itemId) { copy(unitPrice = unitPrice) }

    private fun update(itemId: Long, change: CreditLineUi.() -> CreditLineUi) = holder.update {
        holder.priced(copy(lines = lines.map { if (it.itemId == itemId) it.change() else it }, problem = null))
    }
}

/**
 * The approval of a credit note by a manager or an administrator, with their own PIN (UC-H04). The
 * session does not change.
 */
class CreditNoteApproval internal constructor(private val holder: CreditNoteStateHolder) {

    /** @param approverId the approver picked; the PIN typed so far is cleared. */
    fun choose(approverId: Long) = update { copy(chosen = approverId, pin = "", error = null) }

    /** @param pin the PIN of the approver after a key of the pad was pressed. */
    fun onPinChange(pin: String) = update { copy(pin = pin, error = null) }

    /** Gives the approval up: what was typed stays. */
    fun cancel() = holder.update { copy(approval = null) }

    /** Checks the PIN of the approver; once approved, the credit note is issued with that approval. */
    fun submit() {
        val typed = holder.form?.takeUnless { it.busy }
        val dialog = typed?.approval?.takeIf { it.chosen != null }
        val actor = holder.sessions.current.value?.userId
        if (typed == null || dialog == null || actor == null) return
        val approver = UserId(checkNotNull(dialog.chosen))
        holder.state.value = typed.copy(busy = true)
        holder.scope.launch {
            val action = SensitiveAction.CreditNote(typed.number)
            val error = holder.useCases.authorize.refusalOf(actor, approver, dialog.pin, action)
            val idle = typed.copy(busy = false)
            if (error == null) {
                holder.issue(idle.copy(approval = null), approver)
            } else {
                holder.state.value = idle.copy(approval = dialog.copy(pin = "", error = error))
            }
        }
    }

    private fun update(change: ApprovalUiState.() -> ApprovalUiState) = holder.update {
        copy(approval = approval?.change())
    }
}
