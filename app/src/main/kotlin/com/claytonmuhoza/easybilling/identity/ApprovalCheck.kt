package com.claytonmuhoza.easybilling.identity

import com.claytonmuhoza.easybilling.domain.identity.AuthorizeSensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.SensitiveAction
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.ui.storageCall

/**
 * @param requester the user who asks for an approval.
 * @return the approval to show: those who may approve an action of [requester]; none when they
 * cannot be read.
 */
suspend fun AuthorizeSensitiveAction.approvalFor(requester: UserId): ApprovalUiState {
    val approvers = storageCall { approvers(requester) }.getOrDefault(emptyList())
    return ApprovalUiState(approvers.map { Approver(it.id.value, it.name) })
}

/**
 * Checks the PIN typed in an approval.
 *
 * @param requester the user who asks for the approval.
 * @param approver the approver chosen.
 * @param pin the PIN typed.
 * @param action what is approved.
 * @return why the action is not approved, or null when it is.
 */
suspend fun AuthorizeSensitiveAction.refusalOf(
    requester: UserId,
    approver: UserId,
    pin: String,
    action: SensitiveAction,
): ApprovalError? =
    when (val result = storageCall { this(requester, approver, pin, action) }.getOrNull()) {
        is AuthorizeSensitiveAction.Result.Authorized -> null
        is AuthorizeSensitiveAction.Result.WrongPin -> ApprovalError.WrongPin(result.remainingAttempts)
        is AuthorizeSensitiveAction.Result.Locked -> ApprovalError.Locked
        AuthorizeSensitiveAction.Result.NotAllowed, null -> ApprovalError.NotAllowed
    }
