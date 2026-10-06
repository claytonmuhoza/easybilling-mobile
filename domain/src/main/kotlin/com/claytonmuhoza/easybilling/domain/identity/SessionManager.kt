package com.claytonmuhoza.easybilling.domain.identity

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/**
 * The user currently logged in.
 *
 * @property userId identifier of the user.
 * @property userName name of the user.
 * @property roles roles of the user.
 */
data class Session(val userId: UserId, val userName: String, val roles: Set<Role>) {
    /**
     * @param permission what the user wants to do.
     * @param policy the rights matrix.
     * @return true if one of the roles of the user gives [permission].
     */
    fun can(permission: Permission, policy: PermissionPolicy = FixedPermissionPolicy): Boolean =
        policy.allows(roles, permission)
}

/** Why a session was closed. */
enum class LogoutReason {
    /** The user asked to log out. */
    MANUAL,

    /** Nobody used the application for the inactivity timeout. */
    INACTIVITY,

    /** Another user took the session over with a quick switch (UC-H03). */
    SWITCH,
}

/**
 * Holds the session of the application and closes it after a period without user action.
 *
 * The session lives in memory only: restarting the application always requires a new login.
 *
 * @param time source of the current time.
 * @param auditLog log that records every opening and closing.
 * @param inactivityTimeout time without user action after which the session expires.
 */
class SessionManager(
    private val time: TimeProvider,
    private val auditLog: AuditLog,
    private val inactivityTimeout: Duration,
) {
    private val session = MutableStateFlow<Session?>(null)

    @Volatile
    private var lastActivity: Instant = Instant.EPOCH

    /** The open session, or null when nobody is logged in. */
    val current: StateFlow<Session?> = session.asStateFlow()

    /**
     * Opens a session for a user whose identity was just verified.
     *
     * @param user the verified user.
     */
    suspend fun open(user: User) {
        lastActivity = time.now()
        session.value = Session(user.id, user.name, user.roles)
        auditLog.record(AuditEvent.SessionOpened(user.id))
    }

    /**
     * Hands the session over to a user whose identity was just verified, in one step: the
     * application never shows a moment without a session.
     *
     * @param user the verified user.
     */
    suspend fun switchTo(user: User) {
        val previous = session.value
        lastActivity = time.now()
        session.value = Session(user.id, user.name, user.roles)
        previous?.let { auditLog.record(AuditEvent.SessionClosed(it.userId, LogoutReason.SWITCH)) }
        auditLog.record(AuditEvent.SessionOpened(user.id))
    }

    /**
     * Takes the new name and roles of [user] if the open session is theirs.
     *
     * @param user an account that was just changed.
     */
    fun refresh(user: User) {
        session.update { open -> if (open?.userId == user.id) Session(user.id, user.name, user.roles) else open }
    }

    /** Records a user action, which postpones the expiry of the session. */
    fun touch() {
        lastActivity = time.now()
    }

    /**
     * Closes the session, if any.
     *
     * @param reason why the session is closed.
     */
    suspend fun close(reason: LogoutReason) {
        val closed = session.value ?: return
        session.value = null
        auditLog.record(AuditEvent.SessionClosed(closed.userId, reason))
    }

    /**
     * Closes the session if nobody used the application for the inactivity timeout. A clock set
     * back in time also closes it, so changing the device time cannot keep a session alive.
     *
     * @return true if a session was closed.
     */
    suspend fun expireIfInactive(): Boolean {
        if (session.value == null) return false
        val now = time.now()
        val expired = now < lastActivity || Duration.between(lastActivity, now) >= inactivityTimeout
        if (expired) {
            close(LogoutReason.INACTIVITY)
        }
        return expired
    }
}
