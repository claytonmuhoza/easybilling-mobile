package com.claytonmuhoza.easybilling.numbering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingProblem
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings
import com.claytonmuhoza.easybilling.domain.configuration.SaveNumbering
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.IdentifierContext
import com.claytonmuhoza.easybilling.domain.invoicing.ReadIdentifierContext
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * What the numbering gives next.
 *
 * @property numbers the next number of each type of document; a type whose counter is exhausted
 * is absent.
 * @property identifier the identifier of the next invoice, or null while the tax profile or the
 * system identifier is not known.
 */
data class NumberingPreview(val numbers: Map<DocumentType, String>, val identifier: String?)

/** Result of the last save, shown above the button. */
enum class NumberingFeedback {
    /** The numbering is stored. */
    SAVED,

    /** The numbering could not be written. */
    FAILED,
}

/** State of the numbering screen. */
sealed interface NumberingUiState {
    /** The numbering is being read. */
    data object Loading : NumberingUiState

    /** The numbering could not be read. */
    data object Failed : NumberingUiState

    /**
     * The numbering can be composed.
     *
     * @property fields the fields as chosen.
     * @property preview what the fields give, or null while they break a rule.
     * @property problems the rules the fields break.
     * @property busy true while the numbering is written.
     * @property feedback result of the last save, or null.
     */
    data class Editing(
        val fields: NumberingFields,
        val preview: NumberingPreview?,
        val problems: Set<NumberingProblem> = emptySet(),
        val busy: Boolean = false,
        val feedback: NumberingFeedback? = null,
    ) : NumberingUiState

    /**
     * A number was given: the numbering is shown and can no longer change.
     *
     * @property settings the numbering.
     * @property lockedAt when the first number was given.
     * @property preview the next numbers.
     */
    data class Locked(
        val settings: NumberingSettings,
        val lockedAt: Instant,
        val preview: NumberingPreview,
    ) : NumberingUiState
}

/** Drives the numbering screen (UC-A06). */
@HiltViewModel
class NumberingViewModel @Inject constructor(
    private val numbering: NumberingRepository,
    private val saveNumbering: SaveNumbering,
    private val readIdentifierContext: ReadIdentifierContext,
    private val sessions: SessionManager,
    private val time: TimeProvider,
) : ViewModel() {

    private var context = IdentifierContext(tin = null, activeSystemId = null, roomForNumber = null)

    private val mutableState = MutableStateFlow<NumberingUiState>(NumberingUiState.Loading)

    /** The state to show. */
    val state: StateFlow<NumberingUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the numbering, or proposes the default one. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { read() }.getOrElse { NumberingUiState.Failed }
        }
    }

    /** @param change the change of one field; the preview and the problems follow at once. */
    fun onFieldsChange(change: NumberingFields.() -> NumberingFields) {
        val editing = mutableState.value as? NumberingUiState.Editing ?: return
        if (!editing.busy) mutableState.value = editing(editing.fields.change())
    }

    /** Saves the numbering, or shows what is wrong. */
    fun save() {
        val editing = mutableState.value as? NumberingUiState.Editing ?: return
        val actor = sessions.current.value?.userId
        if (editing.busy || actor == null) return
        mutableState.value = editing.copy(busy = true, feedback = null)
        viewModelScope.launch {
            mutableState.value = storageCall {
                when (saveNumbering(editing.fields.toForm(), context.roomForNumber, actor)) {
                    SaveNumbering.Result.Saved -> editing(editing.fields).copy(feedback = NumberingFeedback.SAVED)
                    is SaveNumbering.Result.Invalid -> editing(editing.fields)
                    SaveNumbering.Result.Locked -> read()
                }
            }.getOrElse { editing.copy(busy = false, feedback = NumberingFeedback.FAILED) }
        }
    }

    private suspend fun read(): NumberingUiState {
        context = readIdentifierContext()
        val stored = numbering.settings()
        val lockedAt = stored?.lockedAt
        return when {
            stored == null -> editing(NumberingFields())
            lockedAt == null -> editing(NumberingFields.from(stored))
            else -> {
                val period = stored.reset.periodOf(time.now().atZone(BusinessCalendar.ZONE).year)
                val lastCounters = DocumentType.entries.associateWith { numbering.lastCounter(it, period) }
                NumberingUiState.Locked(stored, lockedAt, preview(stored, lastCounters))
            }
        }
    }

    private fun editing(fields: NumberingFields): NumberingUiState.Editing =
        when (val check = fields.toForm().check(context.roomForNumber)) {
            is NumberingCheck.Valid -> NumberingUiState.Editing(fields, preview(check.settings))
            is NumberingCheck.Invalid -> NumberingUiState.Editing(fields, preview = null, problems = check.problems)
        }

    private fun preview(
        settings: NumberingSettings,
        lastCounters: Map<DocumentType, Long> = emptyMap(),
    ): NumberingPreview {
        val now = time.now()
        val year = now.atZone(BusinessCalendar.ZONE).year
        val numbers = DocumentType.entries.mapNotNull { type ->
            val next = (lastCounters[type] ?: 0) + 1
            if (next <= settings.maxCounter) type to settings.render(type, year, next) else null
        }.toMap()
        return NumberingPreview(numbers, numbers[DocumentType.FN]?.let { context.identifierOf(now, it) })
    }
}
