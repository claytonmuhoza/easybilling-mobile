package com.claytonmuhoza.easybilling.data.local.invoicing

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.LineAmounts
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import java.math.BigDecimal
import java.time.Instant

/**
 * Row of the `invoices` table: the header of an issued invoice. The table is append only (see
 * `AppendOnlyTables`). The number and the identifier are unique at the database level. [issuedAt]
 * is in milliseconds.
 */
@Entity(tableName = "invoices", indices = [Index("number", unique = true), Index("identifier", unique = true)])
internal data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: String,
    val number: String,
    val identifier: String,
    val issuedAt: Long,
    val profileVersion: Int,
    val environment: String,
    val customerId: Long,
    val customerName: String,
    val customerTin: String?,
    val customerAddress: String,
    val customerVatPayer: Boolean,
    val paymentType: String,
    val currency: String,
    val issuedBy: Long,
)

/**
 * Row of the `invoice_lines` table, append only too. Quantities, rates and amounts are stored as
 * decimal text, never as floating numbers.
 */
@Entity(tableName = "invoice_lines", primaryKeys = ["invoiceId", "position"])
internal data class InvoiceLineEntity(
    val invoiceId: Long,
    val position: Int,
    val itemId: Long,
    val itemCode: String,
    val designation: String,
    val unit: String,
    val stockTracked: Boolean,
    val quantity: String,
    val listUnitPrice: String,
    val priceMode: String,
    val discountKind: String?,
    val discountValue: String?,
    val vatRate: String?,
    val unitPrice: String,
    val price: String,
    val consumptionTax: String,
    val ott: String,
    val tsce: String,
    val priceWithoutVat: String,
    val vat: String,
    val priceWithVat: String,
    val flatRateWithholding: String,
    val total: String,
)

/**
 * Row of the `invoice_statuses` table: where an invoice stands with the OBR. It is the only thing
 * of an invoice that changes. [message] is the refusal of the OBR; [changedAt] is in milliseconds.
 */
@Entity(tableName = "invoice_statuses")
internal data class InvoiceStatusEntity(
    @PrimaryKey val invoiceId: Long,
    val status: String,
    val message: String,
    val changedAt: Long,
)

/** Queries on the issued invoices. */
@Dao
internal interface InvoiceDao {
    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(invoice: InvoiceEntity): Long

    @Insert
    suspend fun insertLines(lines: List<InvoiceLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeStatus(status: InvoiceStatusEntity)

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun find(id: Long): InvoiceEntity?

    @Query("SELECT * FROM invoices WHERE number = :number")
    suspend fun findByNumber(number: String): InvoiceEntity?

    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId ORDER BY position")
    suspend fun lines(invoiceId: Long): List<InvoiceLineEntity>

    @Query("SELECT * FROM invoice_statuses WHERE invoiceId = :invoiceId")
    suspend fun status(invoiceId: Long): InvoiceStatusEntity?

    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM invoices WHERE environment = :environment")
    suspend fun countIn(environment: String): Int
}

/** [InvoiceRepository] backed by the local database. */
internal class RoomInvoiceRepository(private val database: EasyBillingDatabase) : InvoiceRepository {

    private val dao = database.invoiceDao()

    override suspend fun create(content: InvoiceContent): Invoice = database.withTransaction {
        val id = dao.insert(content.toEntity())
        dao.insertLines(content.lines.mapIndexed { position, line -> line.toEntity(id, position) })
        dao.writeStatus(InvoiceStatus.Pending.toEntity(id, content.issuedAt))
        Invoice(InvoiceId(id), content)
    }

    override suspend fun find(id: InvoiceId): Invoice? = database.withTransaction { dao.find(id.value)?.toDomain() }

    override suspend fun findByNumber(number: String): Invoice? =
        database.withTransaction { dao.findByNumber(number)?.toDomain() }

    override suspend fun statusOf(id: InvoiceId): InvoiceStatus? = dao.status(id.value)?.toDomain()

    override suspend fun setStatus(id: InvoiceId, status: InvoiceStatus, at: Instant) = database.withTransaction {
        val current = checkNotNull(dao.status(id.value)) { "No invoice ${id.value}" }.toDomain()
        check(current.canBecome(status)) { "An invoice ${current.code} cannot become ${status.code}" }
        dao.writeStatus(status.toEntity(id.value, at))
    }

    override suspend fun count(): Int = dao.count()

    override suspend fun countIn(environment: EbmsEnvironment): Int = dao.countIn(environment.name)

    private fun InvoiceContent.toEntity() = InvoiceEntity(
        id = 0,
        type = type.name,
        number = number,
        identifier = identifier,
        issuedAt = issuedAt.toEpochMilli(),
        profileVersion = profileVersion,
        environment = environment.name,
        customerId = customer.id.value,
        customerName = customer.name,
        customerTin = customer.tin,
        customerAddress = customer.address,
        customerVatPayer = customer.vatPayer,
        paymentType = paymentType.name,
        currency = currency.name,
        issuedBy = issuedBy.value,
    )

    private suspend fun InvoiceEntity.toDomain(): Invoice {
        val currency = Currency.valueOf(currency)
        return Invoice(
            id = InvoiceId(id),
            content = InvoiceContent(
                type = DocumentType.valueOf(type),
                number = number,
                identifier = identifier,
                issuedAt = Instant.ofEpochMilli(issuedAt),
                profileVersion = profileVersion,
                environment = EbmsEnvironment.valueOf(environment),
                customer = InvoiceCustomer(
                    id = CustomerId(customerId),
                    name = customerName,
                    tin = customerTin,
                    address = customerAddress,
                    vatPayer = customerVatPayer,
                ),
                paymentType = PaymentType.valueOf(paymentType),
                currency = currency,
                lines = dao.lines(id).map { it.toDomain(currency) },
                issuedBy = UserId(issuedBy),
            ),
        )
    }
}

/** Code of [InvoiceStatus.Rejected], the only status that is not an object. */
private const val REJECTED = "REJECTED"

private fun InvoiceStatus.toEntity(invoiceId: Long, at: Instant) = InvoiceStatusEntity(
    invoiceId = invoiceId,
    status = code,
    message = (this as? InvoiceStatus.Rejected)?.message.orEmpty(),
    changedAt = at.toEpochMilli(),
)

private fun InvoiceStatusEntity.toDomain(): InvoiceStatus = when (status) {
    InvoiceStatus.Pending.code -> InvoiceStatus.Pending
    InvoiceStatus.Registered.code -> InvoiceStatus.Registered
    InvoiceStatus.Cancelled.code -> InvoiceStatus.Cancelled
    REJECTED -> InvoiceStatus.Rejected(message)
    else -> error("Unknown invoice status $status")
}

private fun InvoiceLine.toEntity(invoiceId: Long, position: Int) = InvoiceLineEntity(
    invoiceId = invoiceId,
    position = position,
    itemId = itemId.value,
    itemCode = itemCode,
    designation = designation,
    unit = unit,
    stockTracked = stockTracked,
    quantity = quantity.toPlainString(),
    listUnitPrice = listUnitPrice.toPlainString(),
    priceMode = priceMode.name,
    discountKind = discount.kind()?.name,
    discountValue = discount.value(),
    vatRate = vatRate?.stripTrailingZeros()?.toPlainString(),
    unitPrice = amounts.unitPrice.toPlainString(),
    price = amounts.price.toPlainString(),
    consumptionTax = amounts.consumptionTax.toPlainString(),
    ott = amounts.ott.toPlainString(),
    tsce = amounts.tsce.toPlainString(),
    priceWithoutVat = amounts.priceWithoutVat.toPlainString(),
    vat = amounts.vat.toPlainString(),
    priceWithVat = amounts.priceWithVat.toPlainString(),
    flatRateWithholding = amounts.flatRateWithholding.toPlainString(),
    total = amounts.total.toPlainString(),
)

private fun InvoiceLineEntity.toDomain(currency: Currency): InvoiceLine {
    fun money(amount: String) = Money.of(amount, currency)
    return InvoiceLine(
        itemId = ItemId(itemId),
        itemCode = itemCode,
        designation = designation,
        unit = unit,
        stockTracked = stockTracked,
        quantity = Quantity.of(quantity),
        listUnitPrice = money(listUnitPrice),
        priceMode = PriceMode.valueOf(priceMode),
        discount = discountOf(discountKind, discountValue),
        vatRate = vatRate?.let { BigDecimal(it) },
        amounts = LineAmounts(
            unitPrice = money(unitPrice),
            price = money(price),
            consumptionTax = money(consumptionTax),
            ott = money(ott),
            tsce = money(tsce),
            priceWithoutVat = money(priceWithoutVat),
            vat = money(vat),
            priceWithVat = money(priceWithVat),
            flatRateWithholding = money(flatRateWithholding),
            total = money(total),
        ),
    )
}
