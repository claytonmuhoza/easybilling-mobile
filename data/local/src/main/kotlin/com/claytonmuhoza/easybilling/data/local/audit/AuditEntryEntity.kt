package com.claytonmuhoza.easybilling.data.local.audit

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/** Row of the `audit_entries` table. The sequence is the primary key, so it cannot repeat. */
@Entity(tableName = "audit_entries")
internal class AuditEntryEntity(
    @PrimaryKey val sequence: Long,
    val timestampMillis: Long,
    val actorId: Long?,
    val action: String,
    val details: String,
    val previousHash: String,
    val hash: String,
)

internal fun AuditEntryEntity.toDomain() = AuditEntry(
    sequence = sequence,
    timestamp = Instant.ofEpochMilli(timestampMillis),
    actor = actorId?.let(::UserId),
    action = action,
    details = details,
    previousHash = previousHash,
    hash = hash,
)

internal fun AuditEntry.toEntity() = AuditEntryEntity(
    sequence = sequence,
    timestampMillis = timestamp.toEpochMilli(),
    actorId = actor?.value,
    action = action,
    details = details,
    previousHash = previousHash,
    hash = hash,
)
