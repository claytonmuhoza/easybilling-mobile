package com.claytonmuhoza.easybilling.users

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.claytonmuhoza.easybilling.domain.identity.CreateUser
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.domain.identity.PinChoice
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.ResetUserPin
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.SetUserActive
import com.claytonmuhoza.easybilling.domain.identity.UpdateUser
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserForm
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserProblem
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.identity.isConfirmationProblem
import com.claytonmuhoza.easybilling.navigation.UserEditorDestination
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The actions on an account, grouped so the view model receives them as one.
 *
 * @property create creates an account with a temporary PIN.
 * @property update changes the name and the roles of an account.
 * @property setActive disables an account or enables it again.
 * @property resetPin gives a new temporary PIN to an account.
 */
class UserAccountUseCases @Inject constructor(
    val create: CreateUser,
    val update: UpdateUser,
    val setActive: SetUserActive,
    val resetPin: ResetUserPin,
)

/** The steps of the editor of an account, in order. */
enum class UserEditorStep {
    /** The name and the roles. */
    FORM,

    /** The temporary PIN, typed on the PIN pad. */
    PIN,

    /** The temporary PIN a second time. */
    CONFIRMATION,
}

/** What the editor says after an action on an existing account. */
enum class UserNotice {
    /** A new temporary PIN is stored: the user chooses a PIN at the next login. */
    PIN_RESET,

    /** Nobody disables the account they are logged in with. */
    OWN_ACCOUNT,

    /** The change would leave no active administrator. */
    LAST_ADMINISTRATOR,
}

/** State of the editor of an account. */
sealed interface UserEditorUiState {
    /** The account is being read. */
    data object Loading : UserEditorUiState

    /** The account could not be read. */
    data object Failed : UserEditorUiState

    /** There is no such account. */
    data object NotFound : UserEditorUiState

    /** The account is stored: the screen is left. */
    data object Saved : UserEditorUiState

    /**
     * The account is being typed.
     *
     * @property existing the stored account, or null for a new one.
     * @property name the name typed.
     * @property roles the roles ticked.
     * @property step the step shown.
     * @property pin the temporary PIN typed so far.
     * @property confirmation the temporary PIN confirmation typed so far.
     * @property problem the rule of the name or the roles the last save broke, or null.
     * @property pinError problem with the PIN submitted at the step shown, or null.
     * @property notice what the last action on the account says, or null.
     * @property busy true while an action runs.
     * @property failed true if the last action could not be stored.
     */
    data class Editing(
        val existing: User?,
        val name: String = existing?.name.orEmpty(),
        val roles: Set<Role> = existing?.roles.orEmpty(),
        val step: UserEditorStep = UserEditorStep.FORM,
        val pin: String = "",
        val confirmation: String = "",
        val problem: UserProblem? = null,
        val pinError: PinProblem? = null,
        val notice: UserNotice? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : UserEditorUiState
}

/**
 * Drives the creation of an account with its temporary PIN, and the change of an account: its
 * name and roles, its PIN reset, its disabling (UC-H01).
 */
@HiltViewModel
class UserEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val users: UserRepository,
    private val useCases: UserAccountUseCases,
    private val sessions: SessionManager,
) : ViewModel() {

    private val id: Long = savedStateHandle.toRoute<UserEditorDestination>().id

    private val mutableState = MutableStateFlow<UserEditorUiState>(UserEditorUiState.Loading)

    /** The state to show. */
    val state: StateFlow<UserEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Reads the account to change; a new account starts empty. Also used to retry. */
    fun load() {
        if (id == UserEditorDestination.NEW_USER) {
            mutableState.value = UserEditorUiState.Editing(existing = null)
            return
        }
        mutableState.value = UserEditorUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall { users.find(UserId(id)) }.fold(
                onSuccess = { user ->
                    user?.let { UserEditorUiState.Editing(existing = it) } ?: UserEditorUiState.NotFound
                },
                onFailure = { UserEditorUiState.Failed },
            )
        }
    }

    /** @param name the new content of the name field. */
    fun onNameChange(name: String) = updateEditing { copy(name = name, problem = null, notice = null) }

    /**
     * @param role the role ticked or unticked.
     * @param ticked whether the account holds it now.
     */
    fun onRoleChange(role: Role, ticked: Boolean) =
        updateEditing { copy(roles = if (ticked) roles + role else roles - role, problem = null, notice = null) }

    /** @param pin the temporary PIN after a key of the pad was pressed. A new PIN needs a new confirmation. */
    fun onPinChange(pin: String) = updateEditing { copy(pin = pin, confirmation = "", pinError = null) }

    /** @param confirmation the PIN confirmation after a key of the pad was pressed. */
    fun onConfirmationChange(confirmation: String) =
        updateEditing { copy(confirmation = confirmation, pinError = null) }

    /**
     * Submits the step shown. A new account goes on to its temporary PIN and is created at the
     * confirmation, where the name and the roles are checked too; an existing one is saved.
     */
    fun next() {
        val editing = (mutableState.value as? UserEditorUiState.Editing)?.takeUnless { it.busy } ?: return
        when (editing.step) {
            UserEditorStep.FORM -> if (editing.existing == null) {
                mutableState.value = editing.copy(step = UserEditorStep.PIN, notice = null)
            } else {
                act(editing) { actor -> useCases.save(this, editing.existing, actor) }
            }
            // The PIN is checked against itself: only the confirmation step can find a mismatch.
            UserEditorStep.PIN -> mutableState.value = when (val choice = Pin.choose(editing.pin, editing.pin)) {
                is PinChoice.Accepted -> editing.copy(step = UserEditorStep.CONFIRMATION)
                is PinChoice.Refused -> editing.copy(pinError = choice.problem)
            }
            UserEditorStep.CONFIRMATION -> act(editing) { actor ->
                val user = editing.existing
                if (user == null) useCases.create(this, actor) else useCases.resetPin(this, user, actor)
            }
        }
    }

    /**
     * Returns to the previous step, keeping what was typed.
     *
     * @return false on the form, which has no previous step: the screen is then left.
     */
    fun back(): Boolean {
        val editing = mutableState.value as? UserEditorUiState.Editing
        val previous = when (editing?.step) {
            UserEditorStep.CONFIRMATION -> UserEditorStep.PIN
            UserEditorStep.PIN -> UserEditorStep.FORM
            UserEditorStep.FORM, null -> null
        }
        if (editing != null && previous != null) {
            mutableState.value = editing.copy(step = previous, pinError = null)
        }
        return previous != null
    }

    /** Starts the choice of a new temporary PIN for an existing account. */
    fun startPinReset() = updateEditing {
        copy(step = UserEditorStep.PIN, pin = "", confirmation = "", pinError = null, notice = null)
    }

    /** Disables the account, or enables it again. */
    fun toggleActive() {
        val editing = mutableState.value as? UserEditorUiState.Editing ?: return
        val user = editing.existing ?: return
        act(editing) { actor ->
            when (useCases.setActive(user.id, !user.active, actor)) {
                is SetUserActive.Result.Done -> copy(existing = user.copy(active = !user.active))
                SetUserActive.Result.OwnAccount -> copy(notice = UserNotice.OWN_ACCOUNT)
                SetUserActive.Result.LastAdministrator -> copy(notice = UserNotice.LAST_ADMINISTRATOR)
                SetUserActive.Result.UnknownUser -> UserEditorUiState.NotFound
            }
        }
    }

    private fun act(
        editing: UserEditorUiState.Editing,
        block: suspend UserEditorUiState.Editing.(UserId) -> UserEditorUiState,
    ) {
        val actor = sessions.current.value?.userId
        if (editing.busy || actor == null) return
        val idle = editing.copy(busy = false, failed = false, notice = null)
        mutableState.value = editing.copy(busy = true, failed = false)
        viewModelScope.launch {
            mutableState.value = storageCall { idle.block(actor) }.getOrElse { idle.copy(failed = true) }
        }
    }

    private fun updateEditing(change: UserEditorUiState.Editing.() -> UserEditorUiState.Editing) {
        val editing = mutableState.value as? UserEditorUiState.Editing ?: return
        mutableState.value = editing.change()
    }
}

/** Saves the name and the roles of [user]. */
private suspend fun UserAccountUseCases.save(
    editing: UserEditorUiState.Editing,
    user: User,
    actor: UserId,
): UserEditorUiState = when (val result = update(user.id, UserForm(editing.name, editing.roles), actor)) {
    is UpdateUser.Result.Updated -> UserEditorUiState.Saved
    is UpdateUser.Result.Refused -> editing.refused(result.problem)
    UpdateUser.Result.UnknownUser -> UserEditorUiState.NotFound
}

/** Creates the account typed in [editing] with its temporary PIN. */
private suspend fun UserAccountUseCases.create(editing: UserEditorUiState.Editing, actor: UserId): UserEditorUiState {
    val form = UserForm(editing.name, editing.roles)
    return when (val result = create(form, editing.pin, editing.confirmation, actor)) {
        is CreateUser.Result.Created -> UserEditorUiState.Saved
        // The PIN is kept: only the form has to be fixed.
        is CreateUser.Result.Refused -> editing.copy(step = UserEditorStep.FORM, problem = result.problem)
        is CreateUser.Result.InvalidPin -> editing.pinRefused(result.problem)
    }
}

/** Gives [user] the temporary PIN typed in [editing]. */
private suspend fun UserAccountUseCases.resetPin(
    editing: UserEditorUiState.Editing,
    user: User,
    actor: UserId,
): UserEditorUiState = when (val result = resetPin(user.id, editing.pin, editing.confirmation, actor)) {
    ResetUserPin.Result.Reset -> editing.copy(
        existing = user.copy(pinTemporary = true),
        step = UserEditorStep.FORM,
        pin = "",
        confirmation = "",
        notice = UserNotice.PIN_RESET,
    )
    is ResetUserPin.Result.InvalidPin -> editing.pinRefused(result.problem)
    ResetUserPin.Result.UnknownUser -> UserEditorUiState.NotFound
}

/** The last administrator is said above the buttons; the other problems under their field. */
private fun UserEditorUiState.Editing.refused(problem: UserProblem): UserEditorUiState.Editing =
    if (problem == UserProblem.LAST_ADMINISTRATOR) {
        copy(notice = UserNotice.LAST_ADMINISTRATOR)
    } else {
        copy(problem = problem)
    }

/** A mismatch is typed again at the confirmation; any other problem is fixed at the PIN step. */
private fun UserEditorUiState.Editing.pinRefused(problem: PinProblem): UserEditorUiState.Editing =
    if (isConfirmationProblem(problem)) {
        copy(confirmation = "", pinError = problem)
    } else {
        copy(step = UserEditorStep.PIN, pinError = problem)
    }
