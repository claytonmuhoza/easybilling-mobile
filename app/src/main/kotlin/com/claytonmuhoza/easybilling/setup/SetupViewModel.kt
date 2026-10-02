package com.claytonmuhoza.easybilling.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.PinProblem
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.identity.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A problem with the name typed in the assistant. */
enum class NameError {
    /** The name is empty. */
    BLANK,

    /** The name is too long. */
    TOO_LONG,
}

/** State of the first launch assistant. */
sealed interface SetupUiState {
    /**
     * The form is shown.
     *
     * @property name the name typed so far.
     * @property pin the PIN typed so far.
     * @property confirmation the PIN confirmation typed so far.
     * @property nameError problem with the submitted name, or null.
     * @property pinError problem with the submitted PIN, or null.
     * @property submitting true while the account is being created.
     */
    data class Form(
        val name: String = "",
        val pin: String = "",
        val confirmation: String = "",
        val nameError: NameError? = null,
        val pinError: PinProblem? = null,
        val submitting: Boolean = false,
    ) : SetupUiState

    /** The account exists; its recovery code, formatted in groups, must be shown. */
    data class Created(val recoveryCode: String) : SetupUiState

    /** The device already had an account: there is nothing to set up. */
    data object AlreadySetUp : SetupUiState
}

/** Drives the first launch assistant (UC-A01): creation of the administrator account. */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val createAdministrator: CreateAdministrator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SetupUiState>(SetupUiState.Form())
    private var createdUser: User? = null

    /** The state to show. */
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    /** @param name the new content of the name field. */
    fun onNameChange(name: String) = updateForm { copy(name = name, nameError = null) }

    /** @param pin the new content of the PIN field. */
    fun onPinChange(pin: String) = updateForm { copy(pin = pin, pinError = null) }

    /** @param confirmation the new content of the PIN confirmation field. */
    fun onConfirmationChange(confirmation: String) = updateForm { copy(confirmation = confirmation, pinError = null) }

    /** Creates the account from the form, or shows what is wrong with it. */
    fun submit() {
        val form = mutableState.value as? SetupUiState.Form ?: return
        if (form.submitting) return
        mutableState.value = form.copy(submitting = true)
        viewModelScope.launch {
            mutableState.value = when (val result = createAdministrator(form.name, form.pin, form.confirmation)) {
                is CreateAdministrator.Result.Created -> {
                    createdUser = result.user
                    SetupUiState.Created(result.recoveryCode.formatted)
                }
                CreateAdministrator.Result.NameBlank -> form.copy(nameError = NameError.BLANK)
                CreateAdministrator.Result.NameTooLong -> form.copy(nameError = NameError.TOO_LONG)
                is CreateAdministrator.Result.InvalidPin -> form.copy(pinError = result.problem)
                CreateAdministrator.Result.AlreadyExists -> SetupUiState.AlreadySetUp
            }
        }
    }

    /**
     * Opens the session of the new administrator once the recovery code was written down.
     *
     * @param onDone called when the session is open.
     */
    fun acknowledgeRecoveryCode(onDone: () -> Unit) {
        val user = createdUser ?: return
        viewModelScope.launch {
            sessions.open(user)
            onDone()
        }
    }

    private fun updateForm(change: SetupUiState.Form.() -> SetupUiState.Form) {
        val form = mutableState.value as? SetupUiState.Form ?: return
        mutableState.value = form.change()
    }
}
