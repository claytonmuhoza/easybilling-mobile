package com.claytonmuhoza.easybilling.domain.audit

import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/**
 * One line of the audit log.
 *
 * @property sequence position in the log, starting at 1, without gap.
 * @property timestamp when the event was recorded, to the millisecond.
 * @property actor the user who acted, or null for an action of the system.
 * @property action stable code of the action.
 * @property details extra facts about the action.
 * @property previousHash [hash] of the previous entry, or [AuditHasher.GENESIS_HASH] for the first.
 * @property hash SHA-256 of every other property, in hexadecimal.
 */
data class AuditEntry(
    val sequence: Long,
    val timestamp: Instant,
    val actor: UserId?,
    val action: String,
    val details: String,
    val previousHash: String,
    val hash: String,
)

/** Storage of the audit entries. It only ever adds entries. */
interface AuditStore {
    /** @return the most recent entry, or null if the log is empty. */
    suspend fun last(): AuditEntry?

    /**
     * Adds an entry at the end of the log.
     *
     * @param entry the entry to add.
     */
    suspend fun append(entry: AuditEntry)

    /** @return every entry, oldest first. */
    suspend fun readAll(): List<AuditEntry>
}

/** Result of [AuditLog.verify]. */
sealed interface AuditVerification {
    /** Every entry is where and what it was when recorded. */
    data object Intact : AuditVerification

    /** The log was altered. [sequence] is the first entry that does not fit the chain. */
    data class Tampered(val sequence: Long) : AuditVerification
}

/** The audit log (UC-H05): append only, each entry chained to the previous one by its hash. */
interface AuditLog {
    /**
     * Adds an event to the log.
     *
     * @param event what happened.
     */
    suspend fun record(event: AuditEvent)

    /**
     * Checks the whole chain.
     *
     * @return whether an entry was modified, removed, inserted or reordered.
     */
    suspend fun verify(): AuditVerification
}
