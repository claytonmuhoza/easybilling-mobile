package com.claytonmuhoza.easybilling.data.local.transmission

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import java.time.Instant

/**
 * Row of the `outbox_entries` table: an operation to send to the OBR. The identifier gives the
 * order of the queue. [kind] names the operation and [invoiceId] is the invoice it sends;
 * [createdAt] is in milliseconds.
 */
@Entity(tableName = "outbox_entries")
internal data class OutboxEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String,
    val invoiceId: Long,
    val createdAt: Long,
)

/** Queries on the send queue. */
@Dao
internal interface OutboxDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(entry: OutboxEntryEntity): Long

    @Query("SELECT * FROM outbox_entries ORDER BY id")
    suspend fun all(): List<OutboxEntryEntity>
}

/** [OutboxRepository] backed by the local database. */
internal class RoomOutboxRepository(private val dao: OutboxDao) : OutboxRepository {

    override suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry {
        val entity = when (operation) {
            is OutboxOperation.SendInvoice ->
                OutboxEntryEntity(0, SEND_INVOICE, operation.invoice.value, createdAt.toEpochMilli())
        }
        return OutboxEntry(dao.insert(entity), operation, createdAt)
    }

    override suspend fun pending(): List<OutboxEntry> = dao.all().map { entity ->
        val operation = when (entity.kind) {
            SEND_INVOICE -> OutboxOperation.SendInvoice(InvoiceId(entity.invoiceId))
            else -> error("Unknown outbox operation ${entity.kind}")
        }
        OutboxEntry(entity.id, operation, Instant.ofEpochMilli(entity.createdAt))
    }

    private companion object {
        const val SEND_INVOICE = "SEND_INVOICE"
    }
}
