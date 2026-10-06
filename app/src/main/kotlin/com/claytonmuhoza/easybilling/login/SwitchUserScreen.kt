package com.claytonmuhoza.easybilling.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.InitialsAvatar
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.PinEntry
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.identity.Pin

/**
 * Quick switch to another user wired to its view model.
 *
 * @param onClose called to leave the screen, once the session changed hands or when the user goes back.
 * @param viewModel holder of the switch state.
 */
@Composable
fun SwitchUserRoute(onClose: () -> Unit, viewModel: SwitchUserViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == SwitchUserUiState.Switched) LaunchedEffect(Unit) { onClose() }
    SwitchUserScreen(
        state = state,
        actions = SwitchUserActions(
            onChoose = viewModel::choose,
            onPinChange = viewModel::onPinChange,
            onSubmit = viewModel::submit,
            onBack = { if (!viewModel.back()) onClose() },
        ),
    )
}

/**
 * What the user can do on the quick switch.
 *
 * @property onChoose called with the account picked.
 * @property onPinChange called when the PIN changes.
 * @property onSubmit called when the PIN is submitted.
 * @property onBack called when the user goes back.
 */
class SwitchUserActions(
    val onChoose: (Long) -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Hands the register over to another user (UC-H03): the account that takes over, then its PIN.
 * The sale being typed and the sales on hold stay.
 *
 * @param state the switch state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun SwitchUserScreen(state: SwitchUserUiState, actions: SwitchUserActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.onBack)
    val header: @Composable ColumnScope.() -> Unit = { Header() }
    when (state) {
        SwitchUserUiState.Loading, SwitchUserUiState.Switched -> LoadingState(modifier)
        is SwitchUserUiState.Choose -> EntryScreen(modifier = modifier, onBack = actions.onBack, header = header) {
            if (state.accounts.isEmpty()) {
                Text(stringResource(R.string.switch_user_none), textAlign = TextAlign.Center)
            } else {
                AccountChoice(stringResource(R.string.switch_user_title), state.accounts, actions.onChoose)
            }
        }
        is SwitchUserUiState.Pin -> EntryScreen(modifier = modifier, onBack = actions.onBack, header = header) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                InitialsAvatar(state.account.name)
                Text(text = state.account.name, style = MaterialTheme.typography.titleMedium)
            }
            val error = state.error?.message()
            PinEntry(
                pin = state.pin,
                onPinChange = actions.onPinChange,
                maxLength = Pin.MAX_LENGTH,
                onSubmit = actions.onSubmit,
                submitDescription = stringResource(R.string.switch_user),
                slots = Pin.MIN_LENGTH,
                message = error ?: stringResource(R.string.login_instruction),
                isError = error != null,
                enabled = !state.submitting,
            )
        }
    }
}

@Composable
private fun Header() {
    IconTile(AppIcon.SWITCH_ACCOUNT)
    Text(
        text = stringResource(R.string.switch_user),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SwitchError.message(): String = when (this) {
    is SwitchError.WrongPin -> pluralStringResource(R.plurals.login_wrong_pin, remainingAttempts, remainingAttempts)
    SwitchError.Locked -> stringResource(R.string.reset_locked)
    SwitchError.PinChangeRequired -> stringResource(R.string.switch_user_pin_change_required)
    SwitchError.Disabled -> stringResource(R.string.switch_user_disabled)
}
