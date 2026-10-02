package com.claytonmuhoza.easybilling.data.local.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Queries on the `audit_entries` table. There is deliberately no update and no delete. */
@Dao
internal interface AuditDao {
    @Query("SELECT * FROM audit_entries ORDER BY sequence DESC LIMIT 1")
    suspend fun last(): AuditEntryEntity?

    @Insert
    suspend fun insert(entry: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries ORDER BY sequence")
    suspend fun readAll(): List<AuditEntryEntity>
}
