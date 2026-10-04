package com.claytonmuhoza.easybilling.login

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIconImage
import com.claytonmuhoza.easybilling.core.designsystem.component.BrandMark
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.InitialsAvatar
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.PinEntry
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.ebms.label
import kotlinx.coroutines.delay

/** How often the countdown of a lock is refreshed. */
private const val LOCK_REFRESH_INTERVAL_MILLIS = 1_000L

/** Size of the icon of the EBMS environment label. */
private val EnvironmentIconSize = 16.dp

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
 * Login by PIN: the identity of the business, the user who logs in and the PIN pad. The PIN is
 * never typed with the keyboard of the system.
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
        is LoginUiState.Ready -> EntryScreen(modifier = modifier, header = { BusinessHeader(state) }) {
            val error = state.errorMessage()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                InitialsAvatar(state.userName)
                Column {
                    Text(
                        text = stringResource(R.string.login_title, state.userName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.login_instruction),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            PinEntry(
                pin = state.pin,
                onPinChange = onPinChange,
                maxLength = Pin.MAX_LENGTH,
                onSubmit = onSubmit,
                submitDescription = stringResource(R.string.login_submit),
                slots = Pin.MIN_LENGTH,
                message = error,
                isError = error != null,
                enabled = state.inputEnabled,
            )
            TextButton(onClick = onForgotPin) {
                Text(stringResource(R.string.login_forgot_pin))
            }
        }
    }
}

/** Who the device invoices for: the business of the tax profile, or the application until then. */
@Composable
private fun BusinessHeader(state: LoginUiState.Ready) {
    BrandMark()
    Text(
        text = state.business?.name ?: stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    state.business?.let { business ->
        Text(
            text = stringResource(R.string.login_tin, business.tin),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    state.environment?.let { EnvironmentLabel(it) }
}

/** The EBMS environment the invoices go to, said by an icon and its name. */
@Composable
private fun EnvironmentLabel(environment: EbmsEnvironment) {
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        AppIconImage(
            icon = AppIcon.CLOUD,
            contentDescription = null,
            modifier = Modifier.size(EnvironmentIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.login_environment, environment.label()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
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
