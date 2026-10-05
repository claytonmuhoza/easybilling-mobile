package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrinterSettings
import com.claytonmuhoza.easybilling.domain.document.PrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth

/**
 * The only row of the `printer_settings` table. [printerAddress] and [printerName] are null while
 * no printer is associated.
 */
@Entity(tableName = "printer_settings")
internal class PrinterSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val printerAddress: String?,
    val printerName: String?,
    val width: String,
    val accents: Boolean,
    val autoPrint: Boolean,
) {
    companion object {
        /** Identifier of the only row. */
        const val SINGLE_ROW = 1
    }
}

/** Queries on the `printer_settings` table. */
@Dao
internal interface PrinterSettingsDao {
    @Query("SELECT * FROM printer_settings WHERE id = ${PrinterSettingsEntity.SINGLE_ROW}")
    suspend fun read(): PrinterSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(settings: PrinterSettingsEntity)
}

/** [PrinterSettingsRepository] backed by the local database. */
internal class RoomPrinterSettingsRepository(private val dao: PrinterSettingsDao) : PrinterSettingsRepository {

    override suspend fun read(): PrinterSettings = dao.read()?.let { row ->
        PrinterSettings(
            printer = row.printerAddress?.let { PairedPrinter(it, row.printerName.orEmpty()) },
            width = ReceiptWidth.valueOf(row.width),
            accents = row.accents,
            autoPrint = row.autoPrint,
        )
    } ?: PrinterSettings()

    override suspend fun write(settings: PrinterSettings) = dao.write(
        PrinterSettingsEntity(
            printerAddress = settings.printer?.address,
            printerName = settings.printer?.name,
            width = settings.width.name,
            accents = settings.accents,
            autoPrint = settings.autoPrint,
        ),
    )
}
