package com.claytonmuhoza.easybilling.data.local.audit

import com.claytonmuhoza.easybilling.domain.audit.AuditEntry
import com.claytonmuhoza.easybilling.domain.audit.AuditStore

/** [AuditStore] backed by the local database. */
internal class RoomAuditStore(private val dao: AuditDao) : AuditStore {

    override suspend fun last(): AuditEntry? = dao.last()?.toDomain()

    override suspend fun append(entry: AuditEntry) = dao.insert(entry.toEntity())

    override suspend fun readAll(): List<AuditEntry> = dao.readAll().map { it.toDomain() }
}
