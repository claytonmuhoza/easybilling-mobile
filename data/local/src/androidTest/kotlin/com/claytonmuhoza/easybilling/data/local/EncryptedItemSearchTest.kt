package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UC-B07 on a device or emulator, with the encrypted database: a search among 10 000 items takes
 * less than a second. The times are written in the log under the tag `ItemSearch`.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedItemSearchTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = LocalStorage.encrypted(context, DATABASE_NAME) { ByteArray(KEY_SIZE) { it.toByte() } }

    private val details = ItemDetails(
        designation = "",
        unit = "pièce",
        price = Money.of("1500", Currency.BIF),
        priceMode = PriceMode.INCLUDING_TAXES,
        taxes = emptyMap(),
        stockTracked = true,
    )

    @Before
    fun fillCatalogue() = runBlocking {
        storage.transactions.run {
            repeat(CATALOGUE_SIZE) { rank ->
                val designation = "Article numéro $rank ${WORDS[rank % WORDS.size]}"
                storage.items.create("ART-%05d".format(rank), details.copy(designation = designation))
            }
        }
    }

    @After
    fun deleteDatabase() {
        storage.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun aSearchAmongTenThousandItemsTakesLessThanASecond() {
        for (query in listOf("", "art-09999", "numero 7654", "ibiharage", "introuvable", "9")) {
            val millis = measureTimeMillis { runBlocking { storage.items.search(query, archived = false, limit = LIMIT) } }
            Log.i("ItemSearch", "Search \"$query\" among $CATALOGUE_SIZE items: $millis ms")
            assertTrue(millis < ONE_SECOND_MILLIS, "\"$query\" took $millis ms")
        }
        assertEquals(1, runBlocking { storage.items.search("art-09999", archived = false, limit = LIMIT) }.size)
    }

    private companion object {
        const val DATABASE_NAME = "encrypted-item-search-test.db"
        const val KEY_SIZE = 32
        const val CATALOGUE_SIZE = 10_000
        const val LIMIT = 100
        const val ONE_SECOND_MILLIS = 1_000L
        val WORDS = listOf("ibiharage", "umuceri", "isukari", "amavuta", "savon", "café", "thé")
    }
}
