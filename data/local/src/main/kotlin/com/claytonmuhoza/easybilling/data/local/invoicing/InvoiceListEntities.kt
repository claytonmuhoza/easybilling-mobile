package com.claytonmuhoza.easybilling.data.local.invoicing

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.SearchText
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceFilter
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceListing
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceSummary
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import java.time.Instant

/**
 * Row of the `invoice_sellers` table: the seller a refused invoice is sent again with (UC-D04).
 * The issued row keeps what was issued; this one replaces its tax profile version and its
 * identifier when the invoice is read.
 */
@Entity(tableName = "invoice_sellers")
internal data class InvoiceSellerEntity(
    @PrimaryKey val invoiceId: Long,
    val profileVersion: Int,
    val identifier: String,
)

/** Queries on the seller of the invoices sent again. */
@Dao
internal interface InvoiceSellerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(seller: InvoiceSellerEntity)

    @Query("SELECT * FROM invoice_sellers WHERE invoiceId = :invoiceId")
    suspend fun find(invoiceId: Long): InvoiceSellerEntity?
}

/** An invoice of the list: some columns of `invoices`, with its status. */
internal data class InvoiceSummaryRow(
    val id: Long,
    val type: String,
    val number: String,
    val issuedAt: Long,
    val customerName: String,
    val paymentType: String,
    val currency: String,
    val total: String,
    val status: String,
    val message: String,
)

/** How many invoices have a status. */
internal data class StatusCountRow(val status: String, val count: Int)

/** Queries of the list of the invoices. */
@Dao
internal interface InvoiceListDao {
    /** [pattern] is a `LIKE` pattern whose special characters are escaped with a backslash. */
    @Query(
        """
        SELECT i.id, i.type, i.number, i.issuedAt, i.customerName, i.paymentType, i.currency, i.total,
            s.status, s.message
        FROM invoices i JOIN invoice_statuses s ON s.invoiceId = i.id
        WHERE i.searchText LIKE :pattern ESCAPE '\'
            AND (:status IS NULL OR s.status = :status)
            AND (:since IS NULL OR i.issuedAt >= :since)
            AND (:type IS NULL OR i.type = :type)
        ORDER BY i.issuedAt DESC, i.id DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        pattern: String,
        status: String?,
        since: Long?,
        type: String?,
        limit: Int,
    ): List<InvoiceSummaryRow>

    /** [pattern] is a `LIKE` pattern whose special characters are escaped with a backslash. */
    @Query(
        """
        SELECT s.status AS status, COUNT(*) AS count
        FROM invoices i JOIN invoice_statuses s ON s.invoiceId = i.id
        WHERE i.searchText LIKE :pattern ESCAPE '\' AND (:since IS NULL OR i.issuedAt >= :since)
            AND (:type IS NULL OR i.type = :type)
        GROUP BY s.status
        """,
    )
    suspend fun countByStatus(pattern: String, since: Long?, type: String?): List<StatusCountRow>

    /** @return the credit notes issued on the invoice [number], in the order they were issued. */
    @Query("SELECT * FROM invoices WHERE invoiceRef = :number ORDER BY issuedAt, id")
    suspend fun creditNotesOf(number: String): List<InvoiceEntity>
}

/** [InvoiceListing] backed by the local database. */
internal class RoomInvoiceListing(database: EasyBillingDatabase) : InvoiceListing {

    private val dao = database.invoiceListDao()

    override suspend fun search(filter: InvoiceFilter, limit: Int): List<InvoiceSummary> {
        val since = filter.since?.toEpochMilli()
        return dao.search(filter.pattern(), filter.status?.name, since, filter.type?.name, limit).map { row ->
            InvoiceSummary(
                id = InvoiceId(row.id),
                type = DocumentType.valueOf(row.type),
                number = row.number,
                issuedAt = Instant.ofEpochMilli(row.issuedAt),
                customerName = row.customerName,
                paymentType = PaymentType.valueOf(row.paymentType),
                total = Money.of(row.total, Currency.valueOf(row.currency)),
                status = invoiceStatusOf(row.status, row.message),
            )
        }
    }

    override suspend fun countByStatus(filter: InvoiceFilter): Map<InvoiceStatusKind, Int> =
        dao.countByStatus(filter.pattern(), filter.since?.toEpochMilli(), filter.type?.name)
            .associate { InvoiceStatusKind.valueOf(it.status) to it.count }

    private fun InvoiceFilter.pattern(): String =
        "%" + SearchText.normalize(query).replace(SPECIAL) { "\\${it.value}" } + "%"

    private companion object {
        /** The characters `LIKE` gives a meaning to, and the one that escapes them. */
        val SPECIAL = Regex("""[%_\\]""")
    }
}
