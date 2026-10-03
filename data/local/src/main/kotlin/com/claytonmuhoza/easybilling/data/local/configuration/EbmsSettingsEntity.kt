package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import java.time.Instant

/** The only row of the `ebms_settings` table. The secrets are not here: see [SecretStoreCredentialsVault]. */
@Entity(tableName = "ebms_settings")
internal class EbmsSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val activeEnvironment: String,
    val testServerUrl: String?,
    val productionServerUrl: String?,
    val testLastLoginMillis: Long?,
    val productionLastLoginMillis: Long?,
    val exchangeRetentionDays: Int,
) {
    companion object {
        /** Identifier of the only row. */
        const val SINGLE_ROW = 1
    }
}

/** Queries on the `ebms_settings` table. */
@Dao
internal interface EbmsSettingsDao {
    @Query("SELECT * FROM ebms_settings WHERE id = ${EbmsSettingsEntity.SINGLE_ROW}")
    suspend fun read(): EbmsSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(settings: EbmsSettingsEntity)
}

/** [EbmsSettingsRepository] backed by the local database. */
internal class RoomEbmsSettingsRepository(private val dao: EbmsSettingsDao) : EbmsSettingsRepository {

    override suspend fun read(): EbmsSettings = dao.read()?.toDomain() ?: EbmsSettings()

    override suspend fun write(settings: EbmsSettings) = dao.write(settings.toEntity())

    private fun EbmsSettingsEntity.toDomain() = EbmsSettings(
        activeEnvironment = EbmsEnvironment.valueOf(activeEnvironment),
        serverUrls = listOfNotNull(
            testServerUrl?.let { EbmsEnvironment.TEST to it },
            productionServerUrl?.let { EbmsEnvironment.PRODUCTION to it },
        ).toMap(),
        lastSuccessfulLogins = listOfNotNull(
            testLastLoginMillis?.let { EbmsEnvironment.TEST to Instant.ofEpochMilli(it) },
            productionLastLoginMillis?.let { EbmsEnvironment.PRODUCTION to Instant.ofEpochMilli(it) },
        ).toMap(),
        exchangeRetentionDays = exchangeRetentionDays,
    )

    private fun EbmsSettings.toEntity() = EbmsSettingsEntity(
        activeEnvironment = activeEnvironment.name,
        testServerUrl = serverUrl(EbmsEnvironment.TEST),
        productionServerUrl = serverUrl(EbmsEnvironment.PRODUCTION),
        testLastLoginMillis = lastSuccessfulLogin(EbmsEnvironment.TEST)?.toEpochMilli(),
        productionLastLoginMillis = lastSuccessfulLogin(EbmsEnvironment.PRODUCTION)?.toEpochMilli(),
        exchangeRetentionDays = exchangeRetentionDays,
    )
}
