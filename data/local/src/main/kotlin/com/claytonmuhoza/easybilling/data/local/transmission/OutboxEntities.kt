package com.claytonmuhoza.easybilling.data.local.transmission

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.OutboxRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive
import java.time.Instant

/**
 * Row of the `outbox_entries` table: an operation to send to the OBR. The identifier gives the
 * order of the queue. [kind] names the operation and [invoiceId] is the invoice it sends or cancels. The
 * times are in milliseconds; [nextAttemptAt] is null while the operation can be tried at once.
 */
@Entity(tableName = "outbox_entries")
internal data class OutboxEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String,
    val invoiceId: Long,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long?,
    val uncertain: Boolean,
    val lastError: String,
)

/** Queries on the send queue. */
@Dao
internal interface OutboxDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(entry: OutboxEntryEntity): Long

    @Update
    suspend fun update(entry: OutboxEntryEntity)

    @Query("DELETE FROM outbox_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM outbox_entries ORDER BY id")
    suspend fun all(): List<OutboxEntryEntity>
}

/** [OutboxRepository] backed by the local database. */
internal class RoomOutboxRepository(private val dao: OutboxDao) : OutboxRepository {

    override suspend fun enqueue(operation: OutboxOperation, createdAt: Instant): OutboxEntry {
        val entry = OutboxEntry(id = 0, operation = operation, createdAt = createdAt)
        return entry.copy(id = dao.insert(entry.toEntity()))
    }

    override suspend fun pending(): List<OutboxEntry> = dao.all().map { it.toDomain() }

    override suspend fun update(entry: OutboxEntry) = dao.update(entry.toEntity())

    override suspend fun remove(id: Long) = dao.delete(id)

    private fun OutboxEntry.toEntity() = OutboxEntryEntity(
        id = id,
        kind = when (operation) {
            is OutboxOperation.SendInvoice -> SEND_INVOICE
            is OutboxOperation.CancelInvoice -> CANCEL_INVOICE
        },
        invoiceId = operation.invoice.value,
        createdAt = createdAt.toEpochMilli(),
        attempts = attempts,
        nextAttemptAt = nextAttemptAt?.toEpochMilli(),
        uncertain = uncertain,
        lastError = lastError,
    )

    private fun OutboxEntryEntity.toDomain() = OutboxEntry(
        id = id,
        operation = when (kind) {
            SEND_INVOICE -> OutboxOperation.SendInvoice(InvoiceId(invoiceId))
            CANCEL_INVOICE -> OutboxOperation.CancelInvoice(InvoiceId(invoiceId))
            else -> error("Unknown outbox operation $kind")
        },
        createdAt = Instant.ofEpochMilli(createdAt),
        attempts = attempts,
        nextAttemptAt = nextAttemptAt?.let { Instant.ofEpochMilli(it) },
        uncertain = uncertain,
        lastError = lastError,
    )

    private companion object {
        const val SEND_INVOICE = "SEND_INVOICE"
        const val CANCEL_INVOICE = "CANCEL_INVOICE"
    }
}

/**
 * Row of the `invoice_receipts` table: the acknowledgement of an invoice by the OBR, one at most
 * per invoice. The table is append only (see `AppendOnlyTables`): an acknowledgement is a proof.
 * [receivedAt] is in milliseconds.
 */
@Entity(tableName = "invoice_receipts")
internal data class InvoiceReceiptEntity(
    @PrimaryKey val invoiceId: Long,
    val registeredNumber: String,
    val registeredDate: String,
    val signature: String,
    val rawResponse: String,
    val receivedAt: Long,
)

/** Queries on the acknowledgements. */
@Dao
internal interface ReceiptDao {
    @Insert
    suspend fun insert(receipt: InvoiceReceiptEntity)

    @Query("SELECT * FROM invoice_receipts WHERE invoiceId = :invoiceId")
    suspend fun find(invoiceId: Long): InvoiceReceiptEntity?
}

/** [ReceiptArchive] backed by the local database. */
internal class RoomReceiptArchive(private val dao: ReceiptDao) : ReceiptArchive {

    override suspend fun archive(invoice: InvoiceId, receipt: ObrReceipt, receivedAt: Instant) = dao.insert(
        InvoiceReceiptEntity(
            invoiceId = invoice.value,
            registeredNumber = receipt.registeredNumber,
            registeredDate = receipt.registeredDate,
            signature = receipt.signature,
            rawResponse = receipt.rawResponse,
            receivedAt = receivedAt.toEpochMilli(),
        ),
    )

    override suspend fun find(invoice: InvoiceId): ObrReceipt? = dao.find(invoice.value)?.let {
        ObrReceipt(it.registeredNumber, it.registeredDate, it.signature, it.rawResponse)
    }
}
