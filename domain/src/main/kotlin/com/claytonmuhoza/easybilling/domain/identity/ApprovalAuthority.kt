package com.claytonmuhoza.easybilling.domain.identity

/**
 * Tells who may do a sensitive action: a user allowed to do it alone, or a user allowed to ask
 * for it with the approval of another user allowed to approve a sensitive action, given with their
 * PIN (UC-H04).
 *
 * @param access checks the rights of the user who asks.
 * @param users where the approver is read.
 * @param policy the rights matrix.
 */
class ApprovalAuthority(
    private val access: AccessControl,
    private val users: UserRepository,
    private val policy: PermissionPolicy,
) {
    /**
     * @param actor the user who asks for the action.
     * @param approver the user who approved it, or null when [actor] acts alone.
     * @param alone what [actor] must be allowed to act without an approval.
     * @param withApproval what [actor] must be allowed to ask for the action with an approval.
     * @throws PermissionDeniedException if [actor] may not act alone and no valid approval is given.
     */
    suspend fun require(actor: UserId, approver: UserId?, alone: Permission, withApproval: Permission) {
        if (approver == null) {
            access.require(actor, alone)
            return
        }
        access.require(actor, withApproval)
        val user = users.find(approver)
        val approves = user != null && user.id != actor && user.active &&
            policy.allows(user.roles, Permission.AUTHORIZE_SENSITIVE_ACTION)
        if (!approves) throw PermissionDeniedException(approver, Permission.AUTHORIZE_SENSITIVE_ACTION)
    }
}
