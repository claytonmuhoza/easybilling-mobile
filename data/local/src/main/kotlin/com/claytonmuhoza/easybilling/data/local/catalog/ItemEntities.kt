package com.claytonmuhoza.easybilling.data.local.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.catalog.ItemRepository
import com.claytonmuhoza.easybilling.domain.catalog.SearchText
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money

/**
 * Row of the `items` table. The code and the barcode are unique at the database level; several
 * items may have no barcode. The price is stored as
 * decimal text, never as a floating number. [searchText] is the code and the designation without
 * case nor accents, with the barcode, and [sortText] the designation in the same form.
 */
@Entity(
    tableName = "items",
    indices = [Index("code", unique = true), Index("barcode", unique = true), Index("archived", "sortText")],
)
internal data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val code: String,
    val designation: String,
    val unit: String,
    val price: String,
    val currency: String,
    val priceMode: String,
    val vatTaxId: Long?,
    val consumptionTaxId: Long?,
    val flatRateWithholdingTaxId: Long?,
    val ottTaxId: Long?,
    val tsceTaxId: Long?,
    val stockTracked: Boolean,
    val barcode: String?,
    val archived: Boolean,
    val searchText: String,
    val sortText: String,
)

/** Queries on the `items` table. An item is never deleted. */
@Dao
internal interface ItemDao {
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun find(id: Long): ItemEntity?

    @Query("SELECT * FROM items WHERE code = :code")
    suspend fun findByCode(code: String): ItemEntity?

    @Query("SELECT * FROM items WHERE barcode = :barcode")
    suspend fun findByBarcode(barcode: String): ItemEntity?

    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("UPDATE items SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    /** [pattern] is a `LIKE` pattern whose special characters are escaped with a backslash. */
    @Query(
        """
        SELECT * FROM items
        WHERE archived = :archived AND searchText LIKE :pattern ESCAPE '\'
        ORDER BY sortText, code
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, archived: Boolean, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE archived = :archived")
    suspend fun count(archived: Boolean): Int

    @Query("SELECT COUNT(*) FROM items WHERE archived = 0 AND vatTaxId IS NULL")
    suspend fun countWithoutVat(): Int
}

/** [ItemRepository] backed by the local database. */
internal class RoomItemRepository(private val dao: ItemDao) : ItemRepository {

    override suspend fun find(id: ItemId): Item? = dao.find(id.value)?.toDomain()

    override suspend fun findByCode(code: String): Item? = dao.findByCode(code)?.toDomain()

    override suspend fun findByBarcode(barcode: String): Item? = dao.findByBarcode(barcode)?.toDomain()

    override suspend fun create(code: String, details: ItemDetails): Item {
        val id = dao.insert(entity(id = 0, code = code, details = details, archived = false))
        return Item(ItemId(id), code, details)
    }

    override suspend fun update(id: ItemId, details: ItemDetails) {
        val stored = checkNotNull(dao.find(id.value)) { "No item ${id.value}" }
        dao.update(entity(stored.id, stored.code, details, stored.archived))
    }

    override suspend fun setArchived(id: ItemId, archived: Boolean) = dao.setArchived(id.value, archived)

    override suspend fun search(query: String, archived: Boolean, limit: Int): List<Item> {
        val escaped = SearchText.normalize(query).replace(SPECIAL) { "\\${it.value}" }
        return dao.search("%$escaped%", archived, limit).map { it.toDomain() }
    }

    override suspend fun count(archived: Boolean): Int = dao.count(archived)

    override suspend fun countWithoutVat(): Int = dao.countWithoutVat()

    private fun entity(id: Long, code: String, details: ItemDetails, archived: Boolean) = ItemEntity(
        id = id,
        code = code,
        designation = details.designation,
        unit = details.unit,
        price = details.price.toPlainString(),
        currency = details.price.currency.name,
        priceMode = details.priceMode.name,
        vatTaxId = details.taxes[TaxKind.VAT]?.value,
        consumptionTaxId = details.taxes[TaxKind.CONSUMPTION_TAX]?.value,
        flatRateWithholdingTaxId = details.taxes[TaxKind.FLAT_RATE_WITHHOLDING]?.value,
        ottTaxId = details.taxes[TaxKind.OTT]?.value,
        tsceTaxId = details.taxes[TaxKind.TSCE]?.value,
        stockTracked = details.stockTracked,
        barcode = details.barcode,
        archived = archived,
        searchText = SearchText.of(code, details),
        sortText = SearchText.normalize(details.designation),
    )

    private fun ItemEntity.toDomain() = Item(
        id = ItemId(id),
        code = code,
        details = ItemDetails(
            designation = designation,
            unit = unit,
            price = Money.of(price, Currency.valueOf(currency)),
            priceMode = PriceMode.valueOf(priceMode),
            taxes = listOfNotNull(
                vatTaxId?.let { TaxKind.VAT to TaxId(it) },
                consumptionTaxId?.let { TaxKind.CONSUMPTION_TAX to TaxId(it) },
                flatRateWithholdingTaxId?.let { TaxKind.FLAT_RATE_WITHHOLDING to TaxId(it) },
                ottTaxId?.let { TaxKind.OTT to TaxId(it) },
                tsceTaxId?.let { TaxKind.TSCE to TaxId(it) },
            ).toMap(),
            stockTracked = stockTracked,
            barcode = barcode,
        ),
        archived = archived,
    )

    private companion object {
        /** The characters `LIKE` gives a meaning to, and the one that escapes them. */
        val SPECIAL = Regex("""[%_\\]""")
    }
}
