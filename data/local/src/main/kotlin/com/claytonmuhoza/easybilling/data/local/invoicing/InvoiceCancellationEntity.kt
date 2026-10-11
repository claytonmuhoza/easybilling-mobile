package com.claytonmuhoza.easybilling.data.local.invoicing

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationKind
import com.claytonmuhoza.easybilling.domain.invoicing.CancellationState
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellation
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCancellationRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import java.time.Instant

/**
 * Row of the `invoice_cancellations` table: the last cancellation asked for an invoice. [state] is
 * the name of the state, with the message of the OBR in [refusal] for a refused one; [requestedAt]
 * is in milliseconds.
 */
@Entity(tableName = "invoice_cancellations")
internal data class InvoiceCancellationEntity(
    @PrimaryKey val invoiceId: Long,
    val kind: String,
    val reason: String,
    val requestedBy: Long,
    val approvedBy: Long?,
    val requestedAt: Long,
    val state: String,
    val refusal: String?,
    val replacement: Long?,
)

/** Queries on the `invoice_cancellations` table. */
@Dao
internal interface InvoiceCancellationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cancellation: InvoiceCancellationEntity)

    @Query("SELECT * FROM invoice_cancellations WHERE invoiceId = :invoiceId")
    suspend fun find(invoiceId: Long): InvoiceCancellationEntity?
}

/** [InvoiceCancellationRepository] backed by the local database. */
internal class RoomInvoiceCancellationRepository(private val dao: InvoiceCancellationDao) :
    InvoiceCancellationRepository {

    override suspend fun save(cancellation: InvoiceCancellation) = dao.save(
        with(cancellation) {
            InvoiceCancellationEntity(
                invoiceId = invoiceId.value,
                kind = kind.name,
                reason = reason,
                requestedBy = requestedBy.value,
                approvedBy = approvedBy?.value,
                requestedAt = requestedAt.toEpochMilli(),
                state = state.code(),
                refusal = (state as? CancellationState.Refused)?.message,
                replacement = replacement?.value,
            )
        },
    )

    override suspend fun find(invoiceId: InvoiceId): InvoiceCancellation? = dao.find(invoiceId.value)?.let {
        InvoiceCancellation(
            invoiceId = InvoiceId(it.invoiceId),
            kind = CancellationKind.valueOf(it.kind),
            reason = it.reason,
            requestedBy = UserId(it.requestedBy),
            approvedBy = it.approvedBy?.let { id -> UserId(id) },
            requestedAt = Instant.ofEpochMilli(it.requestedAt),
            state = stateOf(it.state, it.refusal),
            replacement = it.replacement?.let { id -> InvoiceId(id) },
        )
    }

    private fun CancellationState.code(): String = when (this) {
        CancellationState.Requested -> REQUESTED
        CancellationState.Done -> DONE
        is CancellationState.Refused -> REFUSED
        CancellationState.Abandoned -> ABANDONED
    }

    private fun stateOf(code: String, refusal: String?): CancellationState = when (code) {
        REQUESTED -> CancellationState.Requested
        DONE -> CancellationState.Done
        REFUSED -> CancellationState.Refused(refusal.orEmpty())
        ABANDONED -> CancellationState.Abandoned
        else -> error("Unknown cancellation state $code")
    }

    private companion object {
        const val REQUESTED = "REQUESTED"
        const val DONE = "DONE"
        const val REFUSED = "REFUSED"
        const val ABANDONED = "ABANDONED"
    }
}
