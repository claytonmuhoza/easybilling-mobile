package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog

/**
 * Thrown when a user asks for something none of their roles allows. The screens hide what a user
 * may not do, so this only happens when a screen is wrong or a role changed meanwhile.
 *
 * @property actor the user who asked.
 * @property permission what was asked.
 */
class PermissionDeniedException(val actor: UserId, val permission: Permission) :
    RuntimeException("User ${actor.value} is not allowed: $permission")

/** Stops a use case when the user who asks is not allowed to. */
fun interface AccessControl {
    /**
     * @param actor the user who asks.
     * @param permission what the use case needs.
     * @throws PermissionDeniedException if [actor] is not allowed [permission].
     */
    suspend fun require(actor: UserId, permission: Permission)
}

/**
 * The [AccessControl] of the application. The roles are read from the stored account, so a role
 * taken away or a disabled account applies at once.
 */
class AccessGuard(
    private val users: UserRepository,
    private val policy: PermissionPolicy,
    private val auditLog: AuditLog,
) : AccessControl {
    /**
     * @throws PermissionDeniedException if [actor] is unknown, disabled or has no role that gives
     * [permission]. The refusal is written to the audit log.
     */
    override suspend fun require(actor: UserId, permission: Permission) {
        val user = users.find(actor)
        if (user == null || !user.active || !policy.allows(user.roles, permission)) {
            auditLog.record(AuditEvent.PermissionDenied(actor, permission))
            throw PermissionDeniedException(actor, permission)
        }
    }
}
