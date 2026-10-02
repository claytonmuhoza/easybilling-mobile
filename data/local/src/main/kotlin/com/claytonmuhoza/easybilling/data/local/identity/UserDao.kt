package com.claytonmuhoza.easybilling.data.local.identity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Queries on the `users` table. */
@Dao
internal interface UserDao {
    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT * FROM users ORDER BY id")
    suspend fun findAll(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun find(id: Long): UserEntity?

    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)
}
