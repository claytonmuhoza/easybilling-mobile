package com.claytonmuhoza.easybilling.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.catalog.ArchiveItem
import com.claytonmuhoza.easybilling.domain.catalog.CreateItem
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemForm
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemProblem
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.catalog.ItemRules
import com.claytonmuhoza.easybilling.domain.catalog.ReadItemRules
import com.claytonmuhoza.easybilling.domain.catalog.SuggestItemCode
import com.claytonmuhoza.easybilling.domain.catalog.UpdateItem
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.navigation.ItemEditorDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The actions on an item, grouped so the view model receives them as one.
 *
 * @property create creates an item.
 * @property update changes an item.
 * @property archive archives an item or brings it back.
 * @property suggestCode proposes the code of a new item.
 */
class ItemActionsUseCases @Inject constructor(
    val create: CreateItem,
    val update: UpdateItem,
    val archive: ArchiveItem,
    val suggestCode: SuggestItemCode,
)

/** State of the screen creating or changing an item. */
sealed interface ItemEditorUiState {
    /** The item is being read. */
    data object Loading : ItemEditorUiState

    /** The item could not be read. */
    data object Failed : ItemEditorUiState

    /** There is no item with that identifier. */
    data object NotFound : ItemEditorUiState

    /** The item is stored: the screen can close. */
    data object Saved : ItemEditorUiState

    /**
     * The form is shown.
     *
     * @property existing the item being changed, or null for a new item.
     * @property form the fields as typed.
     * @property rules what the rules of an item depend on: registrations, device code, taxes.
     * @property problems the rules broken at the last attempt.
     * @property busy true while a change is written.
     * @property failed true when the last change could not be written.
     */
    data class Editing(
        val existing: Item?,
        val form: ItemForm,
        val rules: ItemRules,
        val problems: Set<ItemProblem> = emptySet(),
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : ItemEditorUiState
}

/** Drives the creation, the change and the archiving of an item (UC-B01, UC-B04). */
@HiltViewModel
class ItemEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val items: ItemRepository,
    private val readRules: ReadItemRules,
    private val useCases: ItemActionsUseCases,
    private val sessions: SessionManager,
) : ViewModel() {

    private val id: Long = savedStateHandle.toRoute<ItemEditorDestination>().id

    private val mutableState = MutableStateFlow<ItemEditorUiState>(ItemEditorUiState.Loading)

    /** The state to show. */
    val state: StateFlow<ItemEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the item, or prepares a form with a suggested code for a new one. Also used to retry. */
    fun load() {
        viewModelScope.launch {
            mutableState.value = storageCall { read() }.getOrElse { ItemEditorUiState.Failed }
        }
    }

    /** @param change the change of one field; its problems are cleared until the next attempt. */
    fun onFormChange(change: ItemForm.() -> ItemForm) {
        val editing = mutableState.value as? ItemEditorUiState.Editing ?: return
        if (!editing.busy) mutableState.value = editing.copy(form = editing.form.change(), problems = emptySet())
    }

    /** Creates the new item or stores the change, or shows what is wrong. */
    fun save() = act { editing, actor ->
        val problems = if (editing.existing == null) {
            (useCases.create(editing.form, actor) as? CreateItem.Result.Invalid)?.problems
        } else {
            when (val result = useCases.update(editing.existing.id, editing.form, actor)) {
                UpdateItem.Result.Updated -> null
                is UpdateItem.Result.Invalid -> result.problems
                UpdateItem.Result.NotFound -> return@act ItemEditorUiState.NotFound
            }
        }
        if (problems == null) ItemEditorUiState.Saved else editing.copy(busy = false, problems = problems)
    }

    /** Archives the item, or brings it back. */
    fun toggleArchived() = act { editing, actor ->
        val item = checkNotNull(editing.existing)
        useCases.archive(item.id, !item.archived, actor)
        read()
    }

    private fun act(block: suspend (ItemEditorUiState.Editing, UserId) -> ItemEditorUiState) {
        val editing = mutableState.value as? ItemEditorUiState.Editing ?: return
        val actor = sessions.current.value?.userId
        if (editing.busy || actor == null) return
        mutableState.value = editing.copy(busy = true, failed = false)
        viewModelScope.launch {
            mutableState.value = storageCall { block(editing, actor) }
                .getOrElse { editing.copy(busy = false, failed = true) }
        }
    }

    private suspend fun read(): ItemEditorUiState {
        val rules = readRules()
        if (id == ItemEditorDestination.NEW_ITEM) {
            val form = ItemForm(code = useCases.suggestCode(rules.devicePrefix))
            return ItemEditorUiState.Editing(existing = null, form = form, rules = rules)
        }
        return items.find(ItemId(id))
            ?.let { ItemEditorUiState.Editing(existing = it, form = ItemForm.from(it), rules = rules) }
            ?: ItemEditorUiState.NotFound
    }
}
