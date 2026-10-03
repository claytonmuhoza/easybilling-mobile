package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Queries on the `tax_profile_versions` table. There is deliberately no update and no delete. */
@Dao
internal interface TaxProfileDao {
    @Query("SELECT * FROM tax_profile_versions ORDER BY number DESC LIMIT 1")
    suspend fun current(): TaxProfileVersionEntity?

    @Query("SELECT * FROM tax_profile_versions ORDER BY number DESC")
    suspend fun history(): List<TaxProfileVersionEntity>

    @Query("SELECT * FROM tax_profile_versions WHERE number = :number")
    suspend fun find(number: Int): TaxProfileVersionEntity?

    @Insert
    suspend fun insert(version: TaxProfileVersionEntity)
}
