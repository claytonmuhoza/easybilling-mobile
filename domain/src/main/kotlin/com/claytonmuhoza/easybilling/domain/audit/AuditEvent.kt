package com.claytonmuhoza.easybilling.domain.audit

import com.claytonmuhoza.easybilling.domain.identity.LogoutReason
import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/**
 * Something worth tracing: who did what. The time is added by the [AuditLog].
 *
 * An event never carries a secret (PIN, recovery code, password, token).
 */
sealed interface AuditEvent {
    /** The user who acted, or null for an action of the system. */
    val actor: UserId?

    /** Stable code of the action, stored in the log. Never rename one: old entries keep it. */
    val action: String

    /** Extra facts about the action, as `key=value` pairs separated by `;`. */
    val details: String get() = ""

    /** The administrator account was created by the first launch assistant. */
    data class AdministratorCreated(override val actor: UserId) : AuditEvent {
        override val action get() = "ADMINISTRATOR_CREATED"
    }

    /** A user logged in. */
    data class SessionOpened(override val actor: UserId) : AuditEvent {
        override val action get() = "SESSION_OPENED"
    }

    /** A session ended, by the user or by inactivity. */
    data class SessionClosed(override val actor: UserId, val reason: LogoutReason) : AuditEvent {
        override val action get() = "SESSION_CLOSED"
        override val details get() = "reason=$reason"
    }

    /** A wrong PIN was typed. */
    data class PinRejected(override val actor: UserId) : AuditEvent {
        override val action get() = "PIN_REJECTED"
    }

    /** A wrong recovery code was typed. */
    data class RecoveryCodeRejected(override val actor: UserId) : AuditEvent {
        override val action get() = "RECOVERY_CODE_REJECTED"
    }

    /** Too many wrong secrets locked the account until [until]. */
    data class AccountLocked(override val actor: UserId, val until: Instant) : AuditEvent {
        override val action get() = "ACCOUNT_LOCKED"
        override val details get() = "until=$until"
    }

    /** The PIN was replaced with the recovery code. */
    data class PinReset(override val actor: UserId) : AuditEvent {
        override val action get() = "PIN_RESET"
    }
}
