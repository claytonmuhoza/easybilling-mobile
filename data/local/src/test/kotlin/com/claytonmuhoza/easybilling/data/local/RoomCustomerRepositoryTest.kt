package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The customers and their search, on a real database file. */
@RunWith(AndroidJUnit4::class)
class RoomCustomerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name: see RoomItemRepositoryTest.
    private val name = "customers.db"
    private val storage = LocalStorage.unencryptedFile(context, name)
    private val customers = storage.customers

    private val company = CustomerDetails(
        name = "Brasserie du Lac SA",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.LEGAL_PERSON,
        tin = VerifiedTin("4000202020", "BRASSERIE DU LAC", Instant.parse("2026-10-03T08:00:00.123Z")),
        address = "Bujumbura, Rohero",
        phone = "+257 22 22 22 22",
        vatPayer = true,
    )
    private val tourist = CustomerDetails(
        name = "Émilie Durand",
        origin = CustomerOrigin.FOREIGN,
        personType = TaxpayerType.NATURAL_PERSON,
        tin = null,
        address = "",
        phone = "",
        vatPayer = false,
    )

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private suspend fun names(query: String, archived: Boolean = false, limit: Int = 100) =
        customers.search(query, archived, limit).map { it.details.name }

    @Test
    fun `a new database has no customer`() = runTest {
        assertNull(customers.find(CustomerId(1)))
        assertNull(customers.findByTin("4000202020"))
        assertEquals(0, customers.count(archived = false))
        assertEquals(emptyList(), customers.search("", archived = false, limit = 10))
    }

    @Test
    fun `a customer is read back with every field`() = runTest {
        val first = customers.create(company)
        val second = customers.create(tourist)

        assertEquals(Customer(first.id, company), customers.find(first.id))
        assertEquals(Customer(second.id, tourist), customers.find(second.id))
        assertEquals(Customer(first.id, company), customers.findByTin("4000202020"))
        assertTrue(first.id != second.id)
    }

    @Test
    fun `the counter customer is created once and kept apart`() = runTest {
        val counter = customers.counter()
        customers.create(tourist)

        assertEquals(Customer(counter.id, Customer.counterDetails(), counter = true), counter)
        assertEquals(counter, customers.counter())
        assertEquals(counter, customers.find(counter.id))
        assertEquals(1, customers.count(archived = false))
        assertEquals(listOf("Émilie Durand"), names(""))
        assertEquals(emptyList(), names("comptoir"))
    }

    @Test
    fun `asked for at the same time, the counter customer is still created once`() = runTest {
        val ids = coroutineScope { List(20) { async { customers.counter().id } }.awaitAll() }

        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `the counter customer keeps its role when its name changes`() = runTest {
        val counter = customers.counter()

        customers.update(counter.id, Customer.counterDetails("Client de passage"))

        val renamed = Customer(counter.id, Customer.counterDetails("Client de passage"), counter = true)
        assertEquals(renamed, customers.counter())
    }

    @Test
    fun `the database refuses a TIN used twice, not customers without one`() = runTest {
        customers.create(company)
        customers.create(tourist)
        customers.create(tourist.copy(name = "John Smith"))

        assertFailsWith<SQLiteConstraintException> { customers.create(company.copy(name = "Autre")) }
        assertEquals(3, customers.count(archived = false))
    }

    @Test
    fun `a change replaces the details`() = runTest {
        val customer = customers.create(company)

        customers.update(customer.id, tourist)

        assertEquals(Customer(customer.id, tourist), customers.find(customer.id))
        assertNull(customers.findByTin("4000202020"))
        assertEquals(listOf("Émilie Durand"), names("emilie"))
        assertEquals(emptyList(), names("brasserie"))
    }

    @Test
    fun `an archived customer leaves the active ones and is counted apart`() = runTest {
        val customer = customers.create(company)
        customers.create(tourist)

        customers.setArchived(customer.id, true)

        assertEquals(1, customers.count(archived = false))
        assertEquals(1, customers.count(archived = true))
        assertEquals(listOf("Émilie Durand"), names(""))
        assertEquals(listOf("Brasserie du Lac SA"), names("", archived = true))
        assertEquals(true, customers.findByTin("4000202020")?.archived)
    }

    @Test
    fun `the search looks in the name, the TIN and the phone, without case nor accents`() = runTest {
        customers.create(company)
        customers.create(tourist.copy(phone = "79 123 456"))

        assertEquals(listOf("Émilie Durand"), names("EMILIE"))
        assertEquals(listOf("Émilie Durand"), names("  émilie   durand "))
        assertEquals(listOf("Brasserie du Lac SA"), names("0002020"))
        assertEquals(listOf("Émilie Durand"), names("123 456"))
        assertEquals(emptyList(), names("rohero"))
    }

    @Test
    fun `the special characters of the database are searched as plain text`() = runTest {
        customers.create(tourist.copy(name = "100% Bio"))
        customers.create(tourist.copy(name = "Sac_Plus"))
        customers.create(tourist)

        assertEquals(listOf("100% Bio"), names("%"))
        assertEquals(listOf("Sac_Plus"), names("_"))
    }

    @Test
    fun `the results are sorted by name and limited`() = runTest {
        customers.create(tourist.copy(name = "Zacharie"))
        customers.create(tourist.copy(name = "élise"))
        customers.create(tourist.copy(name = "Aline"))

        assertEquals(listOf("Aline", "élise", "Zacharie"), names(""))
        assertEquals(listOf("Aline", "élise"), names("", limit = 2))
    }

    @Test
    fun `a search among 10 000 customers takes less than a second`() {
        runBlocking {
            storage.transactions.run {
                repeat(FILE_SIZE) { rank ->
                    val tin = VerifiedTin("40%08d".format(rank), "OBR $rank", Instant.parse("2026-10-03T08:00:00Z"))
                    val name = "Client numéro $rank ${NAMES[rank % NAMES.size]}"
                    customers.create(company.copy(name = name, tin = tin, phone = "79%06d".format(rank)))
                }
            }
        }

        val searches = listOf("", "numero 7654", "ndayishimiye", "4000009999", "79001234", "introuvable", "9")
        for (query in searches) {
            val millis = measureTimeMillis { runBlocking { customers.search(query, archived = false, limit = 100) } }
            assertTrue(millis < ONE_SECOND_MILLIS, "\"$query\" took $millis ms")
            println("Search \"$query\" among $FILE_SIZE customers: $millis ms")
        }
        assertEquals(FILE_SIZE, runBlocking { customers.count(archived = false) })
        assertEquals(1, runBlocking { customers.search("4000009999", archived = false, limit = 100) }.size)
    }

    private companion object {
        const val FILE_SIZE = 10_000
        const val ONE_SECOND_MILLIS = 1_000L
        val NAMES = listOf("Ndayishimiye", "Niyonzima", "Irakoze", "Hakizimana", "Nkurunziza")
    }
}
