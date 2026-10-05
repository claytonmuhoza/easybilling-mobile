package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import com.claytonmuhoza.easybilling.domain.document.DocumentSettingsRepository

/** The only row of the `document_settings` table. [logo] is the image as a PNG, or null. */
@Entity(tableName = "document_settings")
internal class DocumentSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val footer: String,
    val logo: ByteArray?,
    val showQrCode: Boolean,
) {
    companion object {
        /** Identifier of the only row. */
        const val SINGLE_ROW = 1
    }
}

/** Queries on the `document_settings` table. */
@Dao
internal interface DocumentSettingsDao {
    @Query("SELECT * FROM document_settings WHERE id = ${DocumentSettingsEntity.SINGLE_ROW}")
    suspend fun read(): DocumentSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(settings: DocumentSettingsEntity)
}

/** [DocumentSettingsRepository] backed by the local database. */
internal class RoomDocumentSettingsRepository(private val dao: DocumentSettingsDao) : DocumentSettingsRepository {

    override suspend fun read(): DocumentSettings = dao.read()
        ?.let { DocumentSettings(it.footer, it.logo?.let(::DocumentLogo), it.showQrCode) }
        ?: DocumentSettings()

    override suspend fun write(settings: DocumentSettings) = dao.write(
        DocumentSettingsEntity(footer = settings.footer, logo = settings.logo?.bytes, showQrCode = settings.showQrCode),
    )
}
