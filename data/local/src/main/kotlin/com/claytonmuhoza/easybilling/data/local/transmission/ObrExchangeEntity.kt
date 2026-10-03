package com.claytonmuhoza.easybilling.data.local.transmission

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeLog
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import java.time.Instant

/** Row of the `obr_exchanges` table. The bodies are stored already masked. */
@Entity(tableName = "obr_exchanges", indices = [Index("startedAtMillis")])
internal class ObrExchangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val startedAtMillis: Long,
    val environment: String,
    val service: String,
    val requestBody: String,
    val statusCode: Int?,
    val responseBody: String,
    val durationMillis: Long,
    val failure: String,
)

/** Queries on the `obr_exchanges` table. */
@Dao
internal interface ObrExchangeDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(exchange: ObrExchangeEntity): Long

    @Query("SELECT * FROM obr_exchanges ORDER BY startedAtMillis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ObrExchangeEntity>

    @Query("SELECT * FROM obr_exchanges WHERE id = :id")
    suspend fun find(id: Long): ObrExchangeEntity?

    /** @return the number of deleted rows. */
    @Query("DELETE FROM obr_exchanges WHERE startedAtMillis < :millis")
    suspend fun deleteBefore(millis: Long): Int
}

/** [ExchangeLog] backed by the local database. */
internal class RoomExchangeLog(private val dao: ObrExchangeDao) : ExchangeLog {

    override suspend fun record(exchange: ObrExchange): Long = dao.insert(
        with(exchange) {
            ObrExchangeEntity(
                id = 0,
                startedAtMillis = startedAt.toEpochMilli(),
                environment = environment.name,
                service = service,
                requestBody = requestBody,
                statusCode = statusCode,
                responseBody = responseBody,
                durationMillis = durationMillis,
                failure = failure,
            )
        },
    )

    override suspend fun recent(limit: Int): List<ObrExchange> = dao.recent(limit).map { it.toDomain() }

    override suspend fun find(id: Long): ObrExchange? = dao.find(id)?.toDomain()

    override suspend fun deleteBefore(instant: Instant): Int = dao.deleteBefore(instant.toEpochMilli())

    private fun ObrExchangeEntity.toDomain() = ObrExchange(
        id = id,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
        environment = EbmsEnvironment.valueOf(environment),
        service = service,
        requestBody = requestBody,
        statusCode = statusCode,
        responseBody = responseBody,
        durationMillis = durationMillis,
        failure = failure,
    )
}
