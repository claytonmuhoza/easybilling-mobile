package com.claytonmuhoza.easybilling.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.FormScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.PinField
import com.claytonmuhoza.easybilling.domain.identity.Pin
import kotlinx.coroutines.delay

/** How often the countdown of a lock is refreshed. */
private const val LOCK_REFRESH_INTERVAL_MILLIS = 1_000L

/**
 * Login screen wired to its view model.
 *
 * @param onForgotPin called when the user says the PIN is forgotten.
 * @param viewModel holder of the login state.
 */
@Composable
fun LoginRoute(onForgotPin: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reloaded each time the screen appears: the lock may have changed on the new PIN screen.
    LaunchedEffect(Unit) { viewModel.load() }

    val locked = (state as? LoginUiState.Ready)?.lockSecondsRemaining != null
    LaunchedEffect(locked) {
        while (locked) {
            delay(LOCK_REFRESH_INTERVAL_MILLIS)
            viewModel.refreshLock()
        }
    }

    LoginScreen(
        state = state,
        onPinChange = viewModel::onPinChange,
        onSubmit = viewModel::submit,
        onForgotPin = onForgotPin,
    )
}

/**
 * Login by PIN.
 *
 * @param state the login state.
 * @param onPinChange called when the PIN changes.
 * @param onSubmit called when the user asks to log in.
 * @param onForgotPin called when the user says the PIN is forgotten.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LoginUiState.Loading -> LoadingState(modifier)
        is LoginUiState.Ready -> FormScreen(title = stringResource(R.string.app_name), modifier = modifier) {
            Text(
                text = stringResource(R.string.login_title, state.userName),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(text = stringResource(R.string.login_instruction), style = MaterialTheme.typography.bodyLarge)
            PinField(
                value = state.pin,
                onValueChange = onPinChange,
                label = stringResource(R.string.field_pin),
                maxLength = Pin.MAX_LENGTH,
                errorMessage = state.errorMessage(),
                enabled = state.inputEnabled,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
            )
            Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = state.inputEnabled) {
                Text(stringResource(R.string.login_submit))
            }
            TextButton(onClick = onForgotPin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_forgot_pin))
            }
        }
    }
}

@Composable
private fun LoginUiState.Ready.errorMessage(): String? = when {
    lockSecondsRemaining != null ->
        pluralStringResource(R.plurals.login_locked, lockSecondsRemaining, lockSecondsRemaining)
    remainingAttempts != null ->
        pluralStringResource(R.plurals.login_wrong_pin, remainingAttempts, remainingAttempts)
    else -> null
}
