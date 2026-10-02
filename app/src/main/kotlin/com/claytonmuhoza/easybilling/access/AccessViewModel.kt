package com.claytonmuhoza.easybilling.access

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.LogoutReason
import com.claytonmuhoza.easybilling.domain.identity.Session
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import javax.inject.Inject

/** What the user is allowed to see right now. */
sealed interface AccessState {
    /** The local data is being opened. */
    data object Loading : AccessState

    /** The local data could not be opened. */
    data object Failed : AccessState

    /** The local data is open: one of the three sections of the application is shown. */
    sealed interface Ready : AccessState

    /** The device has no account yet: only the first launch assistant is available. */
    data object SetupRequired : Ready

    /** Nobody is logged in: only the login screens are available. */
    data object LoggedOut : Ready

    /** [session] is open: the application is available. */
    data class LoggedIn(val session: Session) : Ready
}

/** Decides which section of the application is shown, and keeps the session alive or ends it. */
@HiltViewModel
class AccessViewModel @Inject constructor(
    private val users: UserRepository,
    private val sessions: SessionManager,
) : ViewModel() {

    private sealed interface Accounts {
        data object Unknown : Accounts
        data object Unreadable : Accounts
        data class Known(val exist: Boolean) : Accounts
    }

    private val accounts = MutableStateFlow<Accounts>(Accounts.Unknown)

    /** The current access state. */
    val state: StateFlow<AccessState> =
        combine(accounts, sessions.current) { accounts, session ->
            when {
                session != null -> AccessState.LoggedIn(session)
                accounts is Accounts.Known -> if (accounts.exist) AccessState.LoggedOut else AccessState.SetupRequired
                accounts is Accounts.Unreadable -> AccessState.Failed
                else -> AccessState.Loading
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AccessState.Loading)

    init {
        load()
    }

    /** Opens the local data and finds out whether an account exists. Also used to retry. */
    fun load() {
        accounts.value = Accounts.Unknown
        viewModelScope.launch {
            accounts.value = try {
                Accounts.Known(exist = users.count() > 0)
            } catch (_: SQLException) {
                Accounts.Unreadable
            } catch (_: GeneralSecurityException) {
                Accounts.Unreadable
            }
        }
    }

    /** Called when the first launch assistant has created the administrator. */
    fun onSetupFinished() {
        accounts.value = Accounts.Known(exist = true)
    }

    /** Called on every touch: postpones the automatic logout. */
    fun onUserInteraction() = sessions.touch()

    /** Closes the session if nobody used the application for the inactivity timeout. */
    fun checkInactivity() {
        viewModelScope.launch { sessions.expireIfInactive() }
    }

    /** Closes the session at the request of the user. */
    fun logOut() {
        viewModelScope.launch { sessions.close(LogoutReason.MANUAL) }
    }
}
