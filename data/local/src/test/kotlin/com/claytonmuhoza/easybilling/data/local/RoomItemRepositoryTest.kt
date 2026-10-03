package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The items of the catalogue and their search, on a real database file. */
@RunWith(AndroidJUnit4::class)
class RoomItemRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    // Robolectric puts the file in a folder named after the test: on Windows a long test name plus a
    // long file name exceeds the longest path, and the database cannot be opened.
    private val name = "items.db"
    private val storage = LocalStorage.unencryptedFile(context, name)
    private val items = storage.items

    private val soap = ItemDetails(
        designation = "Savon de Marseille",
        unit = "pièce",
        price = Money.of("1250.5", Currency.BIF),
        priceMode = PriceMode.INCLUDING_TAXES,
        taxes = mapOf(
            TaxKind.VAT to TaxId(1),
            TaxKind.CONSUMPTION_TAX to TaxId(2),
            TaxKind.FLAT_RATE_WITHHOLDING to TaxId(3),
            TaxKind.OTT to TaxId(4),
            TaxKind.TSCE to TaxId(5),
        ),
        stockTracked = true,
        barcode = "6001234567890",
    )
    private val service = ItemDetails(
        designation = "Réparation à domicile",
        unit = "heure",
        price = Money.of("20000", Currency.BIF),
        priceMode = PriceMode.EXCLUDING_TAXES,
        taxes = emptyMap(),
        stockTracked = false,
    )

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private suspend fun designations(query: String, archived: Boolean = false, limit: Int = 100) =
        items.search(query, archived, limit).map { it.details.designation }

    @Test
    fun `a new database has no item`() = runTest {
        assertNull(items.find(ItemId(1)))
        assertNull(items.findByCode("ART-0001"))
        assertEquals(0, items.count(archived = false))
        assertEquals(emptyList(), items.search("", archived = false, limit = 10))
    }

    @Test
    fun `an item is read back with every field`() = runTest {
        val first = items.create("ART-0001", soap)
        val second = items.create("ART-0002", service)

        assertEquals(Item(first.id, "ART-0001", soap), items.find(first.id))
        assertEquals(Item(second.id, "ART-0002", service), items.findByCode("ART-0002"))
        assertTrue(first.id != second.id)
    }

    @Test
    fun `the database itself refuses a second item with the same code`() = runTest {
        items.create("ART-0001", soap)

        assertFailsWith<SQLiteConstraintException> { items.create("ART-0001", service) }
        assertEquals(1, items.count(archived = false))
    }

    @Test
    fun `a change replaces the details and keeps the code`() = runTest {
        val item = items.create("ART-0001", soap)

        items.update(item.id, service)

        assertEquals(Item(item.id, "ART-0001", service), items.find(item.id))
        assertEquals(listOf("Réparation à domicile"), designations("reparation"))
        assertEquals(emptyList(), designations("savon"))
    }

    @Test
    fun `an archived item leaves the active items and is counted apart`() = runTest {
        val item = items.create("ART-0001", soap)
        items.create("ART-0002", service)

        items.setArchived(item.id, true)

        assertEquals(1, items.count(archived = false))
        assertEquals(1, items.count(archived = true))
        assertEquals(listOf("Réparation à domicile"), designations(""))
        assertEquals(listOf("Savon de Marseille"), designations("", archived = true))
        assertEquals(true, items.find(item.id)?.archived)
    }

    @Test
    fun `only the active items without VAT are counted as such`() = runTest {
        items.create("ART-0001", soap)
        items.create("ART-0002", service)
        val archived = items.create("ART-0003", service.copy(designation = "Livraison"))
        items.setArchived(archived.id, true)

        assertEquals(1, items.countWithoutVat())
    }


    @Test
    fun `the database refuses a barcode used twice, not items without one`() = runTest {
        items.create("ART-0001", soap)
        items.create("ART-0002", service)
        items.create("ART-0003", service.copy(designation = "Livraison"))

        assertFailsWith<SQLiteConstraintException> { items.create("ART-0004", soap) }
        assertEquals(3, items.count(archived = false))
    }

    @Test
    fun `an item is found by its barcode, exactly or by a part of it`() = runTest {
        val item = items.create("ART-0001", soap)
        items.create("ART-0002", service)

        assertEquals(item, items.findByBarcode("6001234567890"))
        assertNull(items.findByBarcode("600123"))
        assertEquals(listOf("Savon de Marseille"), designations("6001234567890"))
        assertEquals(listOf("Savon de Marseille"), designations("34567"))

        items.update(item.id, soap.copy(barcode = null))
        assertNull(items.findByBarcode("6001234567890"))
        assertEquals(emptyList(), designations("34567"))
    }
    @Test
    fun `the search ignores case and accents in code and designation`() = runTest {
        items.create("ART-0001", soap)
        items.create("SRV-0002", service)
        items.create("ART-0003", soap.copy(designation = "Café moulu 250 g", barcode = null))

        assertEquals(listOf("Café moulu 250 g"), designations("CAFE"))
        assertEquals(listOf("Café moulu 250 g"), designations("  café   moulu "))
        assertEquals(listOf("Réparation à domicile"), designations("srv"))
        assertEquals(listOf("Réparation à domicile"), designations("a domicile"))
        assertEquals(emptyList(), designations("thé"))
    }

    @Test
    fun `the special characters of the database are searched as plain text`() = runTest {
        items.create("ART-0001", soap.copy(designation = "Remise 10% fidélité", barcode = null))
        items.create("ART-0002", soap.copy(designation = "Sac_plastique", barcode = null))
        items.create("ART-0003", soap)

        assertEquals(listOf("Remise 10% fidélité"), designations("10%"))
        assertEquals(listOf("Sac_plastique"), designations("c_p"))
        // As a wildcard, "%" and "_" would find the three items.
        assertEquals(listOf("Remise 10% fidélité"), designations("%"))
        assertEquals(listOf("Sac_plastique"), designations("_"))
    }

    @Test
    fun `the results are sorted by designation and limited`() = runTest {
        items.create("ART-0001", soap.copy(designation = "Zèbre en peluche", barcode = null))
        items.create("ART-0002", soap.copy(designation = "écharpe", barcode = null))
        items.create("ART-0003", soap.copy(designation = "Ananas", barcode = null))

        assertEquals(listOf("Ananas", "écharpe", "Zèbre en peluche"), designations(""))
        assertEquals(listOf("Ananas", "écharpe"), designations("", limit = 2))
    }

    @Test
    fun `a search among 10 000 items takes less than a second`() {
        runBlocking {
            storage.transactions.run {
                repeat(CATALOGUE_SIZE) { rank ->
                    val code = "ART-%05d".format(rank)
                    val designation = "Article numéro $rank ${WORDS[rank % WORDS.size]}"
                    items.create(code, soap.copy(designation = designation, barcode = "60%011d".format(rank)))
                }
            }
        }

        val searches = listOf("", "art-09999", "numero 7654", "ibiharage", "introuvable", "9")
        for (query in searches) {
            val millis = measureTimeMillis { runBlocking { items.search(query, archived = false, limit = 100) } }
            assertTrue(millis < ONE_SECOND_MILLIS, "\"$query\" took $millis ms")
            println("Search \"$query\" among $CATALOGUE_SIZE items: $millis ms")
        }
        assertEquals(CATALOGUE_SIZE, runBlocking { items.count(archived = false) })
        assertEquals(1, runBlocking { items.search("art-09999", archived = false, limit = 100) }.size)
    }

    private companion object {
        const val CATALOGUE_SIZE = 10_000
        const val ONE_SECOND_MILLIS = 1_000L
        val WORDS = listOf("ibiharage", "umuceri", "isukari", "amavuta", "savon", "café", "thé")
    }
}
