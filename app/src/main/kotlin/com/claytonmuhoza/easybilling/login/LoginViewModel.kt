package com.claytonmuhoza.easybilling.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.LogIn
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The business the device invoices for, as shown above the login. Both values are printed on every
 * invoice, so showing them before the login reveals nothing.
 *
 * @property name name or trade name of the tax profile.
 * @property tin tax identification number of the tax profile.
 */
data class BusinessIdentity(val name: String, val tin: String)

/** State of the login screen. */
sealed interface LoginUiState {
    /** The account is being read. */
    data object Loading : LoginUiState

    /**
     * The PIN can be typed.
     *
     * @property userName name of the user who logs in.
     * @property business the business of the tax profile, or null while there is no tax profile.
     * @property environment the active EBMS environment, or null until a connection to it succeeded.
     * @property pin the PIN typed so far.
     * @property remainingAttempts attempts left after a wrong PIN, or null if the last PIN was
     * not wrong.
     * @property lockSecondsRemaining seconds before the end of the lock, or null if not locked.
     * @property submitting true while the PIN is being checked.
     */
    data class Ready(
        val userName: String,
        val business: BusinessIdentity? = null,
        val environment: EbmsEnvironment? = null,
        val pin: String = "",
        val remainingAttempts: Int? = null,
        val lockSecondsRemaining: Int? = null,
        val submitting: Boolean = false,
    ) : LoginUiState {
        /** Whether the PIN can be typed and submitted. */
        val inputEnabled: Boolean get() = !submitting && lockSecondsRemaining == null
    }
}

/** Drives the login by PIN (UC-H02). Until user management arrives, the only user logs in. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val users: UserRepository,
    private val logIn: LogIn,
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val mutableState = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    private var userId: UserId? = null
    private var lockedUntil: Instant? = null

    /** The state to show. */
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    /** Reads the account, its lock and the identity of the business. Called each time the screen is shown. */
    fun load() {
        viewModelScope.launch {
            val user = users.findAll().firstOrNull() ?: return@launch
            userId = user.id
            lockedUntil = user.loginGuard.lockedUntil
            mutableState.value = LoginUiState.Ready(
                userName = user.name,
                business = business(),
                environment = environment(),
                lockSecondsRemaining = lockSecondsRemaining(),
            )
        }
    }

    /** @param pin the PIN after a key of the pad was pressed. */
    fun onPinChange(pin: String) = updateReady { copy(pin = pin) }

    /** Checks the typed PIN. A success opens the session; the screen then leaves by itself. */
    fun submit() {
        val ready = (mutableState.value as? LoginUiState.Ready)?.takeIf { it.inputEnabled } ?: return
        val id = userId ?: return
        mutableState.value = ready.copy(submitting = true)
        viewModelScope.launch {
            val cleared = ready.copy(pin = "", remainingAttempts = null, submitting = false)
            mutableState.value = when (val result = logIn(id, ready.pin)) {
                LogIn.Result.Success, LogIn.Result.UnknownUser -> cleared
                is LogIn.Result.WrongPin -> cleared.copy(remainingAttempts = result.remainingAttempts)
                is LogIn.Result.Locked -> {
                    lockedUntil = result.until
                    cleared.copy(lockSecondsRemaining = lockSecondsRemaining())
                }
            }
        }
    }

    /** Recomputes the countdown of the lock. Called every second while the account is locked. */
    fun refreshLock() = updateReady { copy(lockSecondsRemaining = lockSecondsRemaining()) }

    /** The header falls back to the name of the application when the profile cannot be read. */
    private suspend fun business(): BusinessIdentity? =
        storageCall { profiles.current() }.getOrNull()?.profile?.let { BusinessIdentity(it.name, it.tin) }

    /** Only the settings are read, never the credentials: nobody is logged in yet. */
    private suspend fun environment(): EbmsEnvironment? = storageCall { ebmsSettings.read() }.getOrNull()
        ?.let { settings -> settings.activeEnvironment.takeIf { settings.lastSuccessfulLogin(it) != null } }

    private fun lockSecondsRemaining(): Int? {
        val remaining = lockedUntil?.let { Duration.between(time.now(), it) } ?: return null
        // Rounded up: "1 second" is shown until the lock is really over.
        return remaining.plusMillis(MILLIS_PER_SECOND - 1).seconds.toInt().takeIf { it > 0 }
    }

    private fun updateReady(change: LoginUiState.Ready.() -> LoginUiState.Ready) {
        val ready = mutableState.value as? LoginUiState.Ready ?: return
        mutableState.value = ready.change()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
