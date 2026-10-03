package com.claytonmuhoza.easybilling.domain.catalog

import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Money
import java.text.Normalizer

/** Identifier of an item in the local storage. */
@JvmInline
value class ItemId(val value: Long)

/**
 * The texts of an item that are sent to the OBR, with the sizes of the specification.
 *
 * @property obrName name of the field in the specification.
 * @property maxLength longest accepted value, in characters, after trimming.
 */
enum class ItemField(val obrName: String, val maxLength: Int) {
    /** Code of the item, declared with every stock movement. */
    CODE("item_code", 30),

    /** Designation of the item, written on the invoice lines. */
    DESIGNATION("item_designation", 500),

    /** Measurement unit of the item. */
    UNIT("item_measurement_unit", 20),
}

/**
 * What can change in an item. An invoice copies what it needs when it is issued, so a change never
 * touches an issued invoice.
 *
 * @property designation `item_designation`.
 * @property unit `item_measurement_unit`.
 * @property price the unit price, above zero.
 * @property priceMode whether [price] includes the taxes.
 * @property taxes the tax chosen for each kind, one at most. A tax the business is not registered
 * for is kept but not applied.
 * @property stockTracked whether the item is kept in stock: its movements are declared to the OBR
 * and it cannot be sold without stock. False for a service.
 * @property barcode the barcode printed on the item, unique, or null. It is not sent to the OBR and
 * may change, unlike the code.
 */
data class ItemDetails(
    val designation: String,
    val unit: String,
    val price: Money,
    val priceMode: PriceMode,
    val taxes: Map<TaxKind, TaxId>,
    val stockTracked: Boolean,
    val barcode: String? = null,
)

/**
 * An item of the catalogue (UC-B01). Instances come from [ItemForm.check] or from the storage.
 *
 * @property id identifier in the local storage.
 * @property code `item_code`: unique, in capitals, never changed after the creation.
 * @property details what can change.
 * @property archived whether the item is hidden from the sale (UC-B04). It is never deleted.
 */
data class Item(
    val id: ItemId,
    val code: String,
    val details: ItemDetails,
    val archived: Boolean = false,
) {
    /**
     * @param registrations the taxes the business is registered for.
     * @return whether the item misses the VAT a registered business must charge: it happens to the
     * items created before the business became registered. Such an item is completed before it is sold.
     */
    fun needsVat(registrations: TaxRegistrations): Boolean = registrations.vat && TaxKind.VAT !in details.taxes
}

/** Turns a text into what the local search compares: no case, no accent, single spaces. */
object SearchText {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    /**
     * @param text a code, a designation or what the user typed.
     * @return the text in lower case, without accents nor extra spaces.
     */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim().replace(SPACES, " ")

    /**
     * @param code the code of an item.
     * @param details the rest of the item.
     * @return what a search looks in: the code, the designation and the barcode, normalized.
     */
    fun of(code: String, details: ItemDetails): String =
        normalize("$code ${details.designation} ${details.barcode.orEmpty()}")
}

/** Storage of the items. */
interface ItemRepository {
    /**
     * @param id an identifier.
     * @return the item, or null if there is none.
     */
    suspend fun find(id: ItemId): Item?

    /**
     * @param code a code in capitals.
     * @return the item with that code, archived or not, or null.
     */
    suspend fun findByCode(code: String): Item?

    /**
     * @param barcode a barcode.
     * @return the item with that barcode, archived or not, or null.
     */
    suspend fun findByBarcode(barcode: String): Item?

    /**
     * @param code the code of the new item.
     * @param details the rest of the item.
     * @return the stored item.
     */
    suspend fun create(code: String, details: ItemDetails): Item

    /**
     * @param id the item.
     * @param details what replaces its details.
     */
    suspend fun update(id: ItemId, details: ItemDetails)

    /**
     * @param id the item.
     * @param archived true to archive it, false to bring it back.
     */
    suspend fun setArchived(id: ItemId, archived: Boolean)

    /**
     * @param query what the user typed; an empty text finds every item.
     * @param archived whether to look among the archived items instead of the active ones.
     * @param limit how many items to return at most.
     * @return the items whose code, designation or barcode contains [query], compared with [SearchText],
     * sorted by designation.
     */
    suspend fun search(query: String, archived: Boolean, limit: Int): List<Item>

    /**
     * @param archived whether to count the archived items instead of the active ones.
     * @return how many items there are.
     */
    suspend fun count(archived: Boolean): Int

    /** @return how many active items have no VAT. */
    suspend fun countWithoutVat(): Int
}
