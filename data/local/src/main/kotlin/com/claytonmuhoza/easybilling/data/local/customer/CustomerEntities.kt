package com.claytonmuhoza.easybilling.data.local.customer

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.data.local.EasyBillingDatabase
import com.claytonmuhoza.easybilling.domain.catalog.SearchText
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerDetails
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerRepository
import com.claytonmuhoza.easybilling.domain.customer.CustomerSearchText
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import java.time.Instant

/**
 * Row of the `customers` table. The TIN is unique at the database level; several customers may
 * have none. [tinObrName] and [tinVerifiedAt] (milliseconds since the epoch) are set with [tin].
 * [searchText] is the name, the TIN and the phone number without case nor accents, and [sortText]
 * the name in the same form.
 */
@Entity(
    tableName = "customers",
    indices = [Index("tin", unique = true), Index("counter", "archived", "sortText")],
)
internal data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val name: String,
    val origin: String,
    val personType: String,
    val tin: String?,
    val tinObrName: String?,
    val tinVerifiedAt: Long?,
    val address: String,
    val phone: String,
    val vatPayer: Boolean,
    val archived: Boolean,
    val counter: Boolean,
    val searchText: String,
    val sortText: String,
)

/** Queries on the `customers` table. A customer is never deleted. */
@Dao
internal interface CustomerDao {
    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun find(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE tin = :tin")
    suspend fun findByTin(tin: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE counter = 1 LIMIT 1")
    suspend fun findCounter(): CustomerEntity?

    /** @return the identifier generated for the new row. */
    @Insert
    suspend fun insert(customer: CustomerEntity): Long

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("UPDATE customers SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    /** [pattern] is a `LIKE` pattern whose special characters are escaped with a backslash. */
    @Query(
        """
        SELECT * FROM customers
        WHERE counter = 0 AND archived = :archived AND searchText LIKE :pattern ESCAPE '\'
        ORDER BY sortText, id
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, archived: Boolean, limit: Int): List<CustomerEntity>

    @Query("SELECT COUNT(*) FROM customers WHERE counter = 0 AND archived = :archived")
    suspend fun count(archived: Boolean): Int
}

/** [CustomerRepository] backed by the local database. */
internal class RoomCustomerRepository(private val database: EasyBillingDatabase) : CustomerRepository {

    private val dao = database.customerDao()

    override suspend fun find(id: CustomerId): Customer? = dao.find(id.value)?.toDomain()

    override suspend fun findByTin(tin: String): Customer? = dao.findByTin(tin)?.toDomain()

    // One transaction at a time runs: two callers cannot both find no counter customer and create it.
    override suspend fun counter(): Customer = database.withTransaction {
        dao.findCounter()?.toDomain() ?: stored(Customer.counterDetails(), counter = true)
    }

    override suspend fun create(details: CustomerDetails): Customer = stored(details, counter = false)

    override suspend fun update(id: CustomerId, details: CustomerDetails) {
        val stored = checkNotNull(dao.find(id.value)) { "No customer ${id.value}" }
        dao.update(entity(stored.id, details, stored.archived, stored.counter))
    }

    override suspend fun setArchived(id: CustomerId, archived: Boolean) = dao.setArchived(id.value, archived)

    override suspend fun search(query: String, archived: Boolean, limit: Int): List<Customer> {
        val escaped = SearchText.normalize(query).replace(SPECIAL) { "\\${it.value}" }
        return dao.search("%$escaped%", archived, limit).map { it.toDomain() }
    }

    override suspend fun count(archived: Boolean): Int = dao.count(archived)

    private suspend fun stored(details: CustomerDetails, counter: Boolean): Customer {
        val id = dao.insert(entity(id = 0, details = details, archived = false, counter = counter))
        return Customer(CustomerId(id), details, counter = counter)
    }

    private fun entity(id: Long, details: CustomerDetails, archived: Boolean, counter: Boolean) = CustomerEntity(
        id = id,
        name = details.name,
        origin = details.origin.name,
        personType = details.personType.name,
        tin = details.tin?.number,
        tinObrName = details.tin?.obrName,
        tinVerifiedAt = details.tin?.verifiedAt?.toEpochMilli(),
        address = details.address,
        phone = details.phone,
        vatPayer = details.vatPayer,
        archived = archived,
        counter = counter,
        searchText = CustomerSearchText.of(details),
        sortText = SearchText.normalize(details.name),
    )

    private fun CustomerEntity.toDomain() = Customer(
        id = CustomerId(id),
        details = CustomerDetails(
            name = name,
            origin = CustomerOrigin.valueOf(origin),
            personType = TaxpayerType.valueOf(personType),
            tin = tin?.let { VerifiedTin(it, tinObrName.orEmpty(), Instant.ofEpochMilli(tinVerifiedAt ?: 0)) },
            address = address,
            phone = phone,
            vatPayer = vatPayer,
        ),
        archived = archived,
        counter = counter,
    )

    private companion object {
        /** The characters `LIKE` gives a meaning to, and the one that escapes them. */
        val SPECIAL = Regex("""[%_\\]""")
    }
}
