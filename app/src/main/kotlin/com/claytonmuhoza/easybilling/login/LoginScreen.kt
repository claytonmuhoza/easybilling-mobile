package com.claytonmuhoza.easybilling.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.claytonmuhoza.easybilling.core.designsystem.component.CardListItem
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.InitialsAvatar
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.PinEntry
import com.claytonmuhoza.easybilling.core.designsystem.component.TonalCard
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.Pin
import com.claytonmuhoza.easybilling.ebms.label
import com.claytonmuhoza.easybilling.identity.PinStep
import kotlinx.coroutines.delay

/** How often the countdown of a lock is refreshed. */
private const val LOCK_REFRESH_INTERVAL_MILLIS = 1_000L

/** Size of the icon of the EBMS environment label. */
private val EnvironmentIconSize = 16.dp

/**
 * Login screen wired to its view model.
 *
 * @param onForgotPin called with the identifier of the user who says the PIN is forgotten.
 * @param viewModel holder of the login state.
 */
@Composable
fun LoginRoute(onForgotPin: (Long) -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
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
        actions = LoginActions(
            onChooseUser = viewModel::chooseUser,
            onChangeUser = viewModel::changeUser,
            onPinChange = viewModel::onPinChange,
            onSubmit = viewModel::submit,
            onForgotPin = onForgotPin,
            onNewPinChange = viewModel.temporaryPin::onPinChange,
            onConfirmationChange = viewModel.temporaryPin::onConfirmationChange,
            onNext = viewModel.temporaryPin::next,
            onBack = viewModel.temporaryPin::back,
        ),
    )
}

/**
 * What the user can do on the login screen.
 *
 * @property onChooseUser called with the identifier of the account the user picks.
 * @property onChangeUser called when the user goes back to the choice of the account.
 * @property onPinChange called when the PIN changes.
 * @property onSubmit called when the user asks to log in.
 * @property onForgotPin called with the identifier of the user who says the PIN is forgotten.
 * @property onNewPinChange called when the PIN that replaces a temporary one changes.
 * @property onConfirmationChange called when the confirmation of that PIN changes.
 * @property onNext called when the user submits a step of the choice of that PIN.
 * @property onBack called when the user goes back from a step of the choice of that PIN.
 */
class LoginActions(
    val onChooseUser: (Long) -> Unit = {},
    val onChangeUser: () -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onForgotPin: (Long) -> Unit = {},
    val onNewPinChange: (String) -> Unit = {},
    val onConfirmationChange: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Login by PIN: the identity of the business, the choice of the account when several can log in,
 * the user who logs in and the PIN pad. A temporary PIN is replaced here at the first login. The
 * PIN is never typed with the keyboard of the system.
 *
 * @param state the login state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun LoginScreen(state: LoginUiState, actions: LoginActions, modifier: Modifier = Modifier) {
    when (state) {
        LoginUiState.Loading -> LoadingState(modifier)
        is LoginUiState.ChooseUser -> EntryScreen(
            modifier = modifier,
            header = { BusinessHeader(state.business, state.environment) },
        ) {
            AccountChoice(state, actions.onChooseUser)
        }
        is LoginUiState.Ready -> {
            BackHandler(enabled = state.canChangeUser, onBack = actions.onChangeUser)
            EntryScreen(modifier = modifier, header = { BusinessHeader(state.business, state.environment) }) {
                PinLogin(state, actions)
            }
        }
        is LoginUiState.NewPin -> {
            BackHandler(onBack = actions.onBack)
            NewPinScreen(state, actions, modifier)
        }
    }
}

/** The accounts that can log in, each with its initials. */
@Composable
private fun ColumnScope.AccountChoice(state: LoginUiState.ChooseUser, onChooseUser: (Long) -> Unit) {
    Text(
        text = stringResource(R.string.login_choose_user),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    state.accounts.forEach { account ->
        TonalCard(onClick = { onChooseUser(account.id) }, modifier = Modifier.fillMaxWidth()) {
            CardListItem(
                leadingContent = { InitialsAvatar(account.name) },
                headlineContent = { Text(account.name) },
            )
        }
    }
}

/** The user who logs in, the PIN pad, and the ways out: another account, a forgotten PIN. */
@Composable
private fun ColumnScope.PinLogin(state: LoginUiState.Ready, actions: LoginActions) {
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
        onPinChange = actions.onPinChange,
        maxLength = Pin.MAX_LENGTH,
        onSubmit = actions.onSubmit,
        submitDescription = stringResource(R.string.login_submit),
        slots = Pin.MIN_LENGTH,
        message = error,
        isError = error != null,
        enabled = state.inputEnabled,
    )
    if (state.hasRecoveryCode) {
        TextButton(onClick = { actions.onForgotPin(state.userId) }) {
            Text(stringResource(R.string.login_forgot_pin))
        }
    } else {
        Text(
            text = stringResource(R.string.login_forgot_pin_ask_administrator),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    if (state.canChangeUser) {
        TextButton(onClick = actions.onChangeUser) {
            Text(stringResource(R.string.login_change_user))
        }
    }
}

/** The choice of the user's own PIN, which replaces the temporary one, one step at a time. */
@Composable
private fun NewPinScreen(state: LoginUiState.NewPin, actions: LoginActions, modifier: Modifier) {
    val enabled = !state.submitting
    EntryScreen(
        modifier = modifier,
        onBack = actions.onBack,
        header = {
            BrandMark()
            Text(
                text = stringResource(R.string.login_new_pin_title, state.userName),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.login_new_pin_instruction),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        when (state.step) {
            NewPinStep.PIN -> PinStep(
                title = stringResource(R.string.field_new_pin),
                pin = state.pin,
                onPinChange = actions.onNewPinChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.action_continue),
                hint = stringResource(R.string.pin_rule_hint),
                problem = state.pinError,
                enabled = enabled,
                error = stringResource(R.string.login_new_pin_same).takeIf { state.sameAsTemporary },
            )
            NewPinStep.CONFIRMATION -> PinStep(
                title = stringResource(R.string.field_pin_confirmation),
                pin = state.confirmation,
                onPinChange = actions.onConfirmationChange,
                onSubmit = actions.onNext,
                submitDescription = stringResource(R.string.login_new_pin_submit),
                hint = null,
                problem = state.pinError,
                enabled = enabled,
            )
        }
    }
}

/** Who the device invoices for: the business of the tax profile, or the application until then. */
@Composable
private fun BusinessHeader(business: BusinessIdentity?, environment: EbmsEnvironment?) {
    BrandMark()
    Text(
        text = business?.name ?: stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    business?.let {
        Text(
            text = stringResource(R.string.login_tin, it.tin),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    environment?.let { EnvironmentLabel(it) }
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
