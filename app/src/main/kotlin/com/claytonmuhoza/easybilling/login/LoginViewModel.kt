package com.claytonmuhoza.easybilling.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.ChangeTemporaryPin
import com.claytonmuhoza.easybilling.domain.identity.LogIn
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.User
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

/**
 * An account the user can log in with.
 *
 * @property id identifier of the account.
 * @property name name of the user.
 */
data class LoginAccount(val id: Long, val name: String)

/** The steps of the choice of a PIN that replaces a temporary one, in order. */
enum class NewPinStep {
    /** The new PIN. */
    PIN,

    /** The new PIN a second time. */
    CONFIRMATION,
}

/** State of the login screen. */
sealed interface LoginUiState {
    /** The accounts are being read. */
    data object Loading : LoginUiState

    /**
     * Several accounts can log in: the user picks theirs.
     *
     * @property business the business of the tax profile, or null while there is no tax profile.
     * @property environment the active EBMS environment, or null until a connection to it succeeded.
     * @property accounts the active accounts, oldest first.
     */
    data class ChooseUser(
        val business: BusinessIdentity? = null,
        val environment: EbmsEnvironment? = null,
        val accounts: List<LoginAccount>,
    ) : LoginUiState

    /**
     * The PIN can be typed.
     *
     * @property userName name of the user who logs in.
     * @property userId identifier of the user who logs in.
     * @property business the business of the tax profile, or null while there is no tax profile.
     * @property environment the active EBMS environment, or null until a connection to it succeeded.
     * @property pin the PIN typed so far.
     * @property remainingAttempts attempts left after a wrong PIN, or null if the last PIN was
     * not wrong.
     * @property lockSecondsRemaining seconds before the end of the lock, or null if not locked.
     * @property submitting true while the PIN is being checked.
     * @property canChangeUser true when another account can log in instead.
     * @property hasRecoveryCode true if a forgotten PIN is replaced with the recovery code; false
     * if an administrator resets it.
     */
    data class Ready(
        val userName: String,
        val userId: Long = 0,
        val business: BusinessIdentity? = null,
        val environment: EbmsEnvironment? = null,
        val pin: String = "",
        val remainingAttempts: Int? = null,
        val lockSecondsRemaining: Int? = null,
        val submitting: Boolean = false,
        val canChangeUser: Boolean = false,
        val hasRecoveryCode: Boolean = true,
    ) : LoginUiState {
        /** Whether the PIN can be typed and submitted. */
        val inputEnabled: Boolean get() = !submitting && lockSecondsRemaining == null
    }

    /**
     * The PIN typed is a temporary one given by an administrator: the user chooses their own.
     *
     * @property userName name of the user who logs in.
     * @property step the step shown.
     * @property pin the new PIN typed so far.
     * @property confirmation the new PIN confirmation typed so far.
     * @property pinError problem with the PIN submitted at the step shown, or null.
     * @property sameAsTemporary true when the new PIN is the temporary one.
     * @property submitting true while the PIN is being replaced.
     */
    data class NewPin(
        val userName: String,
        val step: NewPinStep = NewPinStep.PIN,
        val pin: String = "",
        val confirmation: String = "",
        val pinError: PinProblem? = null,
        val sameAsTemporary: Boolean = false,
        val submitting: Boolean = false,
    ) : LoginUiState
}

/**
 * Drives the login by PIN (UC-H02): the choice of the account when there are several, the PIN,
 * and the replacement of a temporary PIN at the first login, by [temporaryPin].
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val users: UserRepository,
    private val logIn: LogIn,
    changeTemporaryPin: ChangeTemporaryPin,
    private val profiles: TaxProfileRepository,
    private val ebmsSettings: EbmsSettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val login = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    private var accounts: List<User> = emptyList()
    private var chosen: User? = null
    private var lockedUntil: Instant? = null
    private var business: BusinessIdentity? = null
    private var environment: EbmsEnvironment? = null

    /** The choice of the user's own PIN after a login with a temporary one. */
    val temporaryPin = TemporaryPinChange(changeTemporaryPin, viewModelScope, ::onTemporaryPinExit)

    /** The state to show: the choice of a new PIN while there is one, the login otherwise. */
    val state: StateFlow<LoginUiState> = combine(login, temporaryPin.state) { login, newPin -> newPin ?: login }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LoginUiState.Loading)

    /**
     * Reads the accounts, their lock and the identity of the business. Called each time the
     * screen is shown: the user chosen before stays chosen while the account can still log in.
     */
    fun load() {
        viewModelScope.launch {
            accounts = storageCall { users.findAll().filter { it.active } }.getOrDefault(emptyList())
            readHeader()
            val previous = accounts.find { it.id == chosen?.id }
            when {
                previous != null -> choose(previous)
                accounts.size == 1 -> choose(accounts.single())
                accounts.isNotEmpty() -> showAccounts()
            }
        }
    }

    /** @param id the account the user picks among the active ones. */
    fun chooseUser(id: Long) {
        accounts.find { it.id.value == id }?.let { choose(it) }
    }

    /** Goes back to the choice of the account, when there are several. */
    fun changeUser() {
        if (accounts.size > 1) showAccounts()
    }

    /** @param pin the PIN after a key of the pad was pressed. */
    fun onPinChange(pin: String) = updateReady { copy(pin = pin) }

    /**
     * Checks the typed PIN. A success opens the session; the screen then leaves by itself. A
     * temporary PIN leads to the choice of the user's own.
     */
    fun submit() {
        val ready = (login.value as? LoginUiState.Ready)?.takeIf { it.inputEnabled } ?: return
        val user = chosen ?: return
        login.value = ready.copy(submitting = true)
        viewModelScope.launch {
            val result = logIn(user.id, ready.pin)
            login.value = ready.copy(pin = "", remainingAttempts = null, submitting = false)
            when (result) {
                LogIn.Result.Success -> Unit
                is LogIn.Result.WrongPin -> updateReady { copy(remainingAttempts = result.remainingAttempts) }
                is LogIn.Result.Locked -> onTemporaryPinExit(TemporaryPinExit.Locked(result.until))
                LogIn.Result.PinChangeRequired -> temporaryPin.start(user, ready.pin)
                // The account was disabled or removed meanwhile: the list of accounts is read again.
                LogIn.Result.Disabled, LogIn.Result.UnknownUser -> onTemporaryPinExit(TemporaryPinExit.AccountChanged)
            }
        }
    }

    /** Recomputes the countdown of the lock. Called every second while the account is locked. */
    fun refreshLock() = updateReady { copy(lockSecondsRemaining = secondsUntil(lockedUntil, time.now())) }

    /** Shows the PIN of the chosen account again, with what ended the login or the choice of a PIN. */
    private fun onTemporaryPinExit(exit: TemporaryPinExit) {
        val user = chosen
        if (user == null || exit == TemporaryPinExit.AccountChanged) {
            chosen = null
            load()
            return
        }
        if (exit is TemporaryPinExit.Locked) lockedUntil = exit.until
        login.value = (login.value as? LoginUiState.Ready ?: return).copy(
            pin = "",
            remainingAttempts = (exit as? TemporaryPinExit.WrongPin)?.remainingAttempts,
            lockSecondsRemaining = secondsUntil(lockedUntil, time.now()),
        )
    }

    private fun choose(user: User) {
        chosen = user
        lockedUntil = user.loginGuard.lockedUntil
        login.value = LoginUiState.Ready(
            userName = user.name,
            userId = user.id.value,
            business = business,
            environment = environment,
            lockSecondsRemaining = secondsUntil(lockedUntil, time.now()),
            canChangeUser = accounts.size > 1,
            hasRecoveryCode = user.credentials.recoveryHash != null,
        )
    }

    private fun showAccounts() {
        login.value = LoginUiState.ChooseUser(
            business = business,
            environment = environment,
            accounts = accounts.map { LoginAccount(it.id.value, it.name) },
        )
    }

    /**
     * Reads the business of the tax profile and the EBMS environment. The header falls back to the
     * name of the application when the profile cannot be read. Only the settings are read, never
     * the credentials: nobody is logged in yet.
     */
    private suspend fun readHeader() {
        business = storageCall { profiles.current() }.getOrNull()?.profile?.let { BusinessIdentity(it.name, it.tin) }
        environment = storageCall { ebmsSettings.read() }.getOrNull()
            ?.let { settings -> settings.activeEnvironment.takeIf { settings.lastSuccessfulLogin(it) != null } }
    }

    private fun updateReady(change: LoginUiState.Ready.() -> LoginUiState.Ready) {
        val ready = login.value as? LoginUiState.Ready ?: return
        login.value = ready.change()
    }
}

/** A thousand milliseconds, to round a lock up to the second. */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * @param lockedUntil end of the lock, or null.
 * @param now the current instant.
 * @return the seconds left before [lockedUntil], rounded up, or null when the account is not locked.
 */
private fun secondsUntil(lockedUntil: Instant?, now: Instant): Int? {
    val remaining = lockedUntil?.let { Duration.between(now, it) } ?: return null
    // Rounded up: "1 second" is shown until the lock is really over.
    return remaining.plusMillis(MILLIS_PER_SECOND - 1).seconds.toInt().takeIf { it > 0 }
}
