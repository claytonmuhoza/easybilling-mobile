package com.claytonmuhoza.easybilling.identity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.AppIcon
import com.claytonmuhoza.easybilling.core.designsystem.component.EntryScreen
import com.claytonmuhoza.easybilling.core.designsystem.component.IconTile
import com.claytonmuhoza.easybilling.core.designsystem.component.InitialsAvatar
import com.claytonmuhoza.easybilling.core.designsystem.component.PinEntry
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.domain.identity.Pin

/** Height of an approver row: a touch target with room for the avatar. */
private val ApproverRowMinHeight = 56.dp

/**
 * Someone who may approve a sensitive action.
 *
 * @property id identifier of the account.
 * @property name name of the user.
 */
data class Approver(val id: Long, val name: String)

/** Why the last PIN did not approve the action. */
sealed interface ApprovalError {
    /** The PIN is wrong; [remainingAttempts] are left before the account of the approver locks. */
    data class WrongPin(val remainingAttempts: Int) : ApprovalError

    /** The account of the approver is locked for now. */
    data object Locked : ApprovalError

    /** The account chosen can no longer approve. */
    data object NotAllowed : ApprovalError
}

/**
 * The approval of a sensitive action by a manager or an administrator (UC-H04).
 *
 * @property approvers who may approve it, oldest account first.
 * @property chosen the approver chosen, or null; the only one when there is a single approver.
 * @property pin the PIN typed so far.
 * @property error why the last PIN did not approve the action, or null.
 */
data class ApprovalUiState(
    val approvers: List<Approver>,
    val chosen: Long? = approvers.singleOrNull()?.id,
    val pin: String = "",
    val error: ApprovalError? = null,
)

/**
 * What the user can do on the approval.
 *
 * @property onChoose called with the approver picked.
 * @property onPinChange called when the PIN changes.
 * @property onSubmit called when the PIN is submitted.
 * @property onCancel called when the action is given up.
 */
class ApprovalActions(
    val onChoose: (Long) -> Unit = {},
    val onPinChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * Asks a manager or an administrator to approve an action with their own PIN, over the screen. The
 * session does not change. The PIN is typed on the PIN pad.
 *
 * @param message what is approved and why it needs an approval.
 * @param state the approval state.
 * @param actions what the user can do.
 * @param busy true while the PIN is being checked.
 */
@Composable
fun ApprovalDialog(message: String, state: ApprovalUiState, actions: ApprovalActions, busy: Boolean) {
    Dialog(
        onDismissRequest = actions.onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ApprovalContent(message, state, actions, busy)
    }
}

/**
 * What [ApprovalDialog] shows, full screen: what is approved, the approvers and the PIN pad.
 *
 * @param message what is approved and why it needs an approval.
 * @param state the approval state.
 * @param actions what the user can do.
 * @param busy true while the PIN is being checked.
 */
@Composable
fun ApprovalContent(message: String, state: ApprovalUiState, actions: ApprovalActions, busy: Boolean) {
    EntryScreen(
        onBack = actions.onCancel,
        header = {
            IconTile(AppIcon.LOCK)
            Text(
                text = stringResource(R.string.approval_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        if (state.approvers.isEmpty()) {
            Text(stringResource(R.string.approval_none), textAlign = TextAlign.Center)
        } else {
            ApproverChoice(state, actions.onChoose, enabled = !busy)
            if (state.chosen != null) {
                val error = state.error?.message()
                PinEntry(
                    pin = state.pin,
                    onPinChange = actions.onPinChange,
                    maxLength = Pin.MAX_LENGTH,
                    onSubmit = actions.onSubmit,
                    submitDescription = stringResource(R.string.approval_submit),
                    slots = Pin.MIN_LENGTH,
                    message = error,
                    isError = error != null,
                    enabled = !busy,
                )
            }
        }
        TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.approval_cancel)) }
    }
}

/** The approvers, one radio button each; the whole row is the touch target. */
@Composable
private fun ColumnScope.ApproverChoice(state: ApprovalUiState, onChoose: (Long) -> Unit, enabled: Boolean) {
    Text(stringResource(R.string.approval_choose), style = MaterialTheme.typography.titleMedium)
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        state.approvers.forEach { approver -> ApproverRow(approver, approver.id == state.chosen, onChoose, enabled) }
    }
}

/** One approver: the radio button, the initials and the name, one touch target. */
@Composable
private fun ApproverRow(approver: Approver, selected: Boolean, onChoose: (Long) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ApproverRowMinHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onChoose(approver.id) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row carries the click and its meaning: the button only shows the state.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        InitialsAvatar(approver.name, modifier = Modifier.padding(horizontal = Spacing.medium))
        Text(approver.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ApprovalError.message(): String = when (this) {
    is ApprovalError.WrongPin -> pluralStringResource(R.plurals.login_wrong_pin, remainingAttempts, remainingAttempts)
    ApprovalError.Locked -> stringResource(R.string.reset_locked)
    ApprovalError.NotAllowed -> stringResource(R.string.approval_not_allowed)
}
