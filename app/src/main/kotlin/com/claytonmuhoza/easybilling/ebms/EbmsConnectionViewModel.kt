package com.claytonmuhoza.easybilling.ebms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.configuration.EbmsConnectionForm
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialField
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.configuration.FieldProblem
import com.claytonmuhoza.easybilling.domain.configuration.SaveEbmsConnection
import com.claytonmuhoza.easybilling.domain.configuration.SwitchEbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.TestEbmsConnection
import com.claytonmuhoza.easybilling.domain.configuration.UrlProblem
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * The PIN asked before going to production.
 *
 * @property pin the PIN typed so far.
 * @property remainingAttempts attempts left after a wrong PIN, or null.
 * @property locked true if the account refuses every attempt for now.
 * @property checking true while the PIN is being checked.
 */
data class PinPrompt(
    val pin: String = "",
    val remainingAttempts: Int? = null,
    val locked: Boolean = false,
    val checking: Boolean = false,
)

/** Result of the last save, shown under the button. */
enum class SaveFeedback {
    /** The settings are saved. */
    SAVED,

    /** The settings could not be written. */
    FAILED,
}

/** State of the EBMS connection screen. */
sealed interface EbmsConnectionUiState {
    /** The settings are being read. */
    data object Loading : EbmsConnectionUiState

    /** The settings could not be read. */
    data object LoadFailed : EbmsConnectionUiState

    /**
     * The settings of the active environment are shown.
     *
     * @property activeEnvironment the environment every call goes to; the form edits its settings.
     * @property form the settings as typed; the password field starts empty.
     * @property hasStoredPassword whether a password is saved for this environment.
     * @property problems refused credential fields of the last save.
     * @property urlProblem why the address was refused at the last save, or null.
     * @property lastSuccess when a login last succeeded with the saved credentials, or null.
     * @property saving true while the settings are being saved.
     * @property saveFeedback result of the last save, or null.
     * @property testing true while the connection is being tested.
     * @property testOutcome result of the last test, or null.
     * @property pinPrompt the PIN asked to go to production, or null when not asked.
     */
    data class Ready(
        val activeEnvironment: EbmsEnvironment,
        val form: EbmsConnectionForm,
        val hasStoredPassword: Boolean,
        val problems: Map<EbmsCredentialField, FieldProblem> = emptyMap(),
        val urlProblem: UrlProblem? = null,
        val lastSuccess: Instant? = null,
        val saving: Boolean = false,
        val saveFeedback: SaveFeedback? = null,
        val testing: Boolean = false,
        val testOutcome: LoginOutcome? = null,
        val pinPrompt: PinPrompt? = null,
    ) : EbmsConnectionUiState
}

/** Drives the settings of the connection to the EBMS (UC-A03 to UC-A05). */
@HiltViewModel
class EbmsConnectionViewModel @Inject constructor(
    private val settings: EbmsSettingsRepository,
    private val vault: EbmsCredentialsVault,
    private val saveConnection: SaveEbmsConnection,
    private val switchEnvironment: SwitchEbmsEnvironment,
    private val testConnection: TestEbmsConnection,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<EbmsConnectionUiState>(EbmsConnectionUiState.Loading)

    /** The state to show. */
    val state: StateFlow<EbmsConnectionUiState> = mutableState.asStateFlow()

    private val actor: UserId? get() = sessions.current.value?.userId

    init {
        load()
    }

    /** Reads the settings of the active environment. Also used to retry. */
    fun load() {
        mutableState.value = EbmsConnectionUiState.Loading
        viewModelScope.launch {
            mutableState.value = storageCall {
                val current = settings.read()
                val environment = current.activeEnvironment
                val credentials = vault.read(environment)
                EbmsConnectionUiState.Ready(
                    activeEnvironment = environment,
                    form = EbmsConnectionForm(
                        serverUrl = current.serverUrl(environment).orEmpty(),
                        username = credentials?.username.orEmpty(),
                    ),
                    hasStoredPassword = credentials != null,
                    lastSuccess = current.lastSuccessfulLogin(environment),
                )
            }.getOrElse { EbmsConnectionUiState.LoadFailed }
        }
    }

    /** @param url the new content of the address field. */
    fun onServerUrlChange(url: String) = edit { copy(form = form.copy(serverUrl = url), urlProblem = null) }

    /**
     * @param field a credential field.
     * @param value its new content.
     */
    fun onFieldChange(field: EbmsCredentialField, value: String) = edit {
        copy(form = form.with(field, value), problems = problems - field)
    }

    /** Saves the address and the credentials of the active environment. */
    fun save() {
        val ready = mutableState.value as? EbmsConnectionUiState.Ready ?: return
        val user = actor
        if (ready.saving || user == null) return
        mutableState.value = ready.copy(saving = true, saveFeedback = null, testOutcome = null)
        viewModelScope.launch {
            val result = storageCall { saveConnection(ready.activeEnvironment, ready.form, user) }.getOrNull()
            mutableState.value = when (result) {
                SaveEbmsConnection.Result.Saved -> ready.copy(
                    form = ready.form.copy(password = ""),
                    hasStoredPassword = true,
                    problems = emptyMap(),
                    urlProblem = null,
                    lastSuccess = null,
                    saveFeedback = SaveFeedback.SAVED,
                )
                is SaveEbmsConnection.Result.Invalid ->
                    ready.copy(problems = result.problems, urlProblem = result.urlProblem)
                null -> ready.copy(saveFeedback = SaveFeedback.FAILED)
            }
        }
    }

    /** Tries a login to the active environment with the saved settings. */
    fun test() {
        val ready = mutableState.value as? EbmsConnectionUiState.Ready ?: return
        val user = actor
        if (ready.testing || user == null) return
        mutableState.value = ready.copy(testing = true, testOutcome = null, saveFeedback = null)
        viewModelScope.launch {
            val outcome = storageCall { testConnection(user) }.getOrElse { LoginOutcome.NotConfigured }
            val lastSuccess = storageCall { settings.read().lastSuccessfulLogin(ready.activeEnvironment) }.getOrNull()
            edit { copy(testing = false, testOutcome = outcome, lastSuccess = lastSuccess) }
        }
    }

    /**
     * Going back to test is immediate; going to production first asks for the PIN.
     *
     * @param environment the environment the user picks.
     */
    fun onEnvironmentSelected(environment: EbmsEnvironment) {
        val ready = mutableState.value as? EbmsConnectionUiState.Ready ?: return
        when {
            environment == ready.activeEnvironment -> Unit
            environment == EbmsEnvironment.PRODUCTION -> mutableState.value = ready.copy(pinPrompt = PinPrompt())
            else -> switch(environment, pin = null)
        }
    }

    /** @param pin the new content of the PIN field. */
    fun onPinChange(pin: String) = edit { copy(pinPrompt = pinPrompt?.copy(pin = pin, remainingAttempts = null)) }

    /** Checks the PIN and goes to production if it is right. */
    fun confirmPin() {
        val prompt = (mutableState.value as? EbmsConnectionUiState.Ready)?.pinPrompt ?: return
        if (prompt.checking) return
        edit { copy(pinPrompt = prompt.copy(checking = true)) }
        switch(EbmsEnvironment.PRODUCTION, prompt.pin)
    }

    /** Closes the PIN prompt without changing the environment. */
    fun dismissPin() = edit { copy(pinPrompt = null) }

    private fun switch(environment: EbmsEnvironment, pin: String?) {
        val user = actor ?: return
        viewModelScope.launch {
            when (val result = storageCall { switchEnvironment(environment, user, pin) }.getOrNull()) {
                SwitchEbmsEnvironment.Result.Switched, SwitchEbmsEnvironment.Result.Unchanged -> load()
                is SwitchEbmsEnvironment.Result.WrongPin -> edit {
                    copy(pinPrompt = PinPrompt(remainingAttempts = result.remainingAttempts))
                }
                is SwitchEbmsEnvironment.Result.Locked -> edit { copy(pinPrompt = PinPrompt(locked = true)) }
                SwitchEbmsEnvironment.Result.UnknownUser, null -> edit { copy(pinPrompt = null) }
            }
        }
    }

    private fun edit(change: EbmsConnectionUiState.Ready.() -> EbmsConnectionUiState.Ready) {
        val ready = mutableState.value as? EbmsConnectionUiState.Ready ?: return
        mutableState.value = ready.change()
    }
}
