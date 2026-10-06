package com.claytonmuhoza.easybilling.users

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.CountedTextField
import com.claytonmuhoza.easybilling.core.designsystem.component.ErrorState
import com.claytonmuhoza.easybilling.core.designsystem.component.FormColumn
import com.claytonmuhoza.easybilling.core.designsystem.component.LoadingState
import com.claytonmuhoza.easybilling.core.designsystem.component.SectionHeader
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.component.TitledScreen
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.identity.CreateAdministrator
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.UserProblem
import com.claytonmuhoza.easybilling.identity.PinStep

/** Height of a role row: a touch target, with room for its two lines. */
private val RoleRowMinHeight = 56.dp

/** The problems said under the name field. */
private val NameProblems = setOf(UserProblem.NAME_BLANK, UserProblem.NAME_TOO_LONG, UserProblem.NAME_TAKEN)

/**
 * Editor of an account wired to its view model.
 *
 * @param onClose called to leave the screen, once the account is stored or when the user goes back.
 * @param viewModel holder of the editor state.
 */
@Composable
fun UserEditorRoute(onClose: () -> Unit, viewModel: UserEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state == UserEditorUiState.Saved) LaunchedEffect(Unit) { onClose() }
    UserEditorScreen(
        state = state,
        actions = UserEditorActions(
            onNameChange = viewModel::onNameChange,
            onRoleChange = viewModel::onRoleChange,
            onPinChange = viewModel::onPinChange,
            onConfirmationChange = viewModel::onConfirmationChange,
            onNext = viewModel::next,
            onResetPin = viewModel::startPinReset,
            onToggleActive = viewModel::toggleActive,
            onRetry = viewModel::load,
            onBack = { if (!viewModel.back()) onClose() },
        ),
    )
}

/**
 * What the user can do on the editor of an account.
 *
 * @property onNameChange called when the name changes.
 * @property onRoleChange called with a role and whether it is now ticked.
 * @property onPinChange called when the temporary PIN changes.
 * @property onConfirmationChange called when the temporary PIN confirmation changes.
 * @property onNext called when the user submits the step shown.
 * @property onResetPin called when the user asks for a new temporary PIN.
 * @property onToggleActive called when the user disables the account or enables it again.
 * @property onRetry called when the user asks to read the account again.
 * @property onBack called when the user returns to the previous step, or leaves from the form.
 */
class UserEditorActions(
    val onNameChange: (String) -> Unit = {},
    val onRoleChange: (Role, Boolean) -> Unit = { _, _ -> },
    val onPinChange: (String) -> Unit = {},
    val onConfirmationChange: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onResetPin: () -> Unit = {},
    val onToggleActive: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * Creation of an account with a temporary PIN, or change of an account: its name and roles, a
 * new temporary PIN, its disabling (UC-H01). The PIN is typed on the PIN pad, one step at a time.
 *
 * @param state the editor state.
 * @param actions what the user can do.
 * @param modifier modifier applied to the screen.
 */
@Composable
fun UserEditorScreen(state: UserEditorUiState, actions: UserEditorActions, modifier: Modifier = Modifier) {
    val editing = state as? UserEditorUiState.Editing
    val title = editing?.existing?.name ?: stringResource(R.string.users_new)
    // On the form the system back button leaves the screen by itself.
    BackHandler(enabled = editing != null && editing.step != UserEditorStep.FORM, onBack = actions.onBack)
    TitledScreen(title = title, modifier = modifier, onBack = actions.onBack) {
        when (state) {
            UserEditorUiState.Loading, UserEditorUiState.Saved -> LoadingState()
            UserEditorUiState.Failed -> ErrorState(
                message = stringResource(R.string.users_load_error),
                onRetry = actions.onRetry,
            )
            UserEditorUiState.NotFound -> ErrorState(message = stringResource(R.string.users_not_found))
            is UserEditorUiState.Editing -> FormColumn {
                when (state.step) {
                    UserEditorStep.FORM -> AccountForm(state, actions)
                    UserEditorStep.PIN, UserEditorStep.CONFIRMATION -> TemporaryPinStep(state, actions)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AccountForm(state: UserEditorUiState.Editing, actions: UserEditorActions) {
    val enabled = !state.busy
    state.existing?.let { UserChips(it) }
    state.notice?.let { Notice(it) }
    CountedTextField(
        value = state.name,
        onValueChange = actions.onNameChange,
        label = stringResource(R.string.field_name),
        maxLength = CreateAdministrator.MAX_NAME_LENGTH,
        errorMessage = state.problem?.takeIf { it in NameProblems }?.message(),
        hint = stringResource(R.string.users_name_hint),
        capitalization = KeyboardCapitalization.Words,
    )
    SectionHeader(stringResource(R.string.users_roles))
    Role.entries.forEach { role ->
        RoleRow(role, ticked = role in state.roles, enabled = enabled) { actions.onRoleChange(role, it) }
    }
    if (state.problem == UserProblem.NO_ROLE) ErrorText(UserProblem.NO_ROLE.message())
    if (state.failed) ErrorText(stringResource(R.string.users_save_error))
    Button(onClick = actions.onNext, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Text(stringResource(if (state.existing == null) R.string.action_continue else R.string.users_save))
    }
    val user = state.existing ?: return
    OutlinedButton(onClick = actions.onResetPin, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Text(stringResource(R.string.users_reset_pin))
    }
    OutlinedButton(onClick = actions.onToggleActive, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        Text(stringResource(if (user.active) R.string.users_disable else R.string.users_enable))
    }
}

/** A role, what it allows, and whether the account holds it. The whole row is one checkbox. */
@Composable
private fun RoleRow(role: Role, ticked: Boolean, enabled: Boolean, onTickedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RoleRowMinHeight)
            .toggleable(
                value = ticked,
                enabled = enabled,
                role = SemanticsRole.Checkbox,
                onValueChange = onTickedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row carries the click and its meaning: the box only shows the state.
        Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = Spacing.medium).weight(1f)) {
            Text(text = role.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = role.help(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The temporary PIN of a new account, or the new temporary PIN of an existing one. */
@Composable
private fun ColumnScope.TemporaryPinStep(state: UserEditorUiState.Editing, actions: UserEditorActions) {
    Text(
        text = stringResource(R.string.users_temporary_pin_help, state.name.trim()),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.failed) ErrorText(stringResource(R.string.users_save_error))
    val enabled = !state.busy
    if (state.step == UserEditorStep.PIN) {
        PinStep(
            title = stringResource(R.string.users_temporary_pin),
            pin = state.pin,
            onPinChange = actions.onPinChange,
            onSubmit = actions.onNext,
            submitDescription = stringResource(R.string.action_continue),
            hint = stringResource(R.string.pin_rule_hint),
            problem = state.pinError,
            enabled = enabled,
        )
    } else {
        PinStep(
            title = stringResource(R.string.field_pin_confirmation),
            pin = state.confirmation,
            onPinChange = actions.onConfirmationChange,
            onSubmit = actions.onNext,
            submitDescription = stringResource(
                if (state.existing == null) R.string.users_create else R.string.users_reset_pin,
            ),
            hint = null,
            problem = state.pinError,
            enabled = enabled,
        )
    }
}

@Composable
private fun Notice(notice: UserNotice) {
    when (notice) {
        UserNotice.PIN_RESET -> StatusBanner(StatusTone.SUCCESS, stringResource(R.string.users_pin_reset_done))
        UserNotice.OWN_ACCOUNT -> StatusBanner(StatusTone.ERROR, stringResource(R.string.error_user_own_account))
        UserNotice.LAST_ADMINISTRATOR -> StatusBanner(StatusTone.ERROR, UserProblem.LAST_ADMINISTRATOR.message())
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
