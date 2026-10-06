package com.claytonmuhoza.easybilling.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.SwitchUser
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the PIN typed did not hand the session over. */
sealed interface SwitchError {
    /** The PIN is wrong; [remainingAttempts] are left before the account locks. */
    data class WrongPin(val remainingAttempts: Int) : SwitchError

    /** The account is locked for now. */
    data object Locked : SwitchError

    /** The account still has a temporary PIN: its first login goes through the login screen. */
    data object PinChangeRequired : SwitchError

    /** The account was disabled meanwhile. */
    data object Disabled : SwitchError
}

/** State of the quick switch to another user. */
sealed interface SwitchUserUiState {
    /** The accounts are being read. */
    data object Loading : SwitchUserUiState

    /**
     * The user picks the account that takes over.
     *
     * @property accounts the other active accounts, oldest first; empty when there is none.
     */
    data class Choose(val accounts: List<LoginAccount>) : SwitchUserUiState

    /**
     * The PIN of the account picked can be typed.
     *
     * @property account the account that takes over.
     * @property pin the PIN typed so far.
     * @property error why the last PIN did not hand the session over, or null.
     * @property submitting true while the PIN is being checked.
     */
    data class Pin(
        val account: LoginAccount,
        val pin: String = "",
        val error: SwitchError? = null,
        val submitting: Boolean = false,
    ) : SwitchUserUiState

    /** The session changed hands: the screen is left. */
    data object Switched : SwitchUserUiState
}

/** Drives the quick switch to another user without closing the register (UC-H03). */
@HiltViewModel
class SwitchUserViewModel @Inject constructor(
    private val users: UserRepository,
    private val sessions: SessionManager,
    private val switchUser: SwitchUser,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SwitchUserUiState>(SwitchUserUiState.Loading)
    private var accounts: List<LoginAccount> = emptyList()

    /** The state to show. */
    val state: StateFlow<SwitchUserUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val current = sessions.current.value?.userId
            accounts = storageCall { users.findAll().filter { it.active && it.id != current } }
                .getOrDefault(emptyList())
                .map { LoginAccount(it.id.value, it.name) }
            mutableState.value = accounts.singleOrNull()?.let { SwitchUserUiState.Pin(it) }
                ?: SwitchUserUiState.Choose(accounts)
        }
    }

    /** @param id the account picked. */
    fun choose(id: Long) {
        accounts.find { it.id == id }?.let { mutableState.value = SwitchUserUiState.Pin(it) }
    }

    /** @param pin the PIN after a key of the pad was pressed. */
    fun onPinChange(pin: String) {
        val shown = mutableState.value as? SwitchUserUiState.Pin ?: return
        mutableState.value = shown.copy(pin = pin, error = null)
    }

    /** Hands the session over if the PIN is right, or says why not. */
    fun submit() {
        val shown = (mutableState.value as? SwitchUserUiState.Pin)?.takeUnless { it.submitting } ?: return
        mutableState.value = shown.copy(submitting = true)
        viewModelScope.launch {
            val cleared = shown.copy(pin = "", submitting = false)
            mutableState.value = when (val result = switchUser(UserId(shown.account.id), shown.pin)) {
                SwitchUser.Result.Switched -> SwitchUserUiState.Switched
                is SwitchUser.Result.WrongPin -> cleared.copy(error = SwitchError.WrongPin(result.remainingAttempts))
                is SwitchUser.Result.Locked -> cleared.copy(error = SwitchError.Locked)
                SwitchUser.Result.PinChangeRequired -> cleared.copy(error = SwitchError.PinChangeRequired)
                SwitchUser.Result.Disabled, SwitchUser.Result.UnknownUser -> cleared.copy(error = SwitchError.Disabled)
            }
        }
    }

    /**
     * Goes back to the choice of the account.
     *
     * @return false when there is no choice to go back to: the screen is then left.
     */
    fun back(): Boolean {
        val back = mutableState.value is SwitchUserUiState.Pin && accounts.size > 1
        if (back) mutableState.value = SwitchUserUiState.Choose(accounts)
        return back
    }
}
