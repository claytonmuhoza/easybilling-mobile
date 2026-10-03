package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.catalog.SearchText
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import java.time.Instant

/** Identifier of a customer in the local storage. */
@JvmInline
value class CustomerId(val value: Long)

/** Where a customer is established. It is not sent to the OBR: it decides whether a TIN is asked. */
enum class CustomerOrigin {
    /** Established in Burundi: a legal person must have a TIN, a natural person may have one. */
    LOCAL,

    /** Established abroad: never a TIN. */
    FOREIGN,
}

/**
 * The texts of a customer that are sent to the OBR, with the sizes of the specification (page 12).
 *
 * @property obrName name of the field in the specification.
 * @property maxLength longest accepted value, in characters, after trimming.
 */
enum class CustomerField(val obrName: String, val maxLength: Int) {
    /** Name of the customer, mandatory on every invoice. */
    NAME("customer_name", 100),

    /** TIN of the customer: optional, but valid when given. */
    TIN("customer_TIN", 50),

    /** Address of the customer. */
    ADDRESS("customer_address", 100),
}

/**
 * A TIN the OBR recognised (`checkTIN/`). A customer never carries a TIN that was not checked.
 *
 * @property number the TIN.
 * @property obrName the name of its owner, as the OBR gave it (`tp_name`).
 * @property verifiedAt when the OBR recognised it.
 */
data class VerifiedTin(val number: String, val obrName: String, val verifiedAt: Instant)

/**
 * What can change in a customer. An invoice copies what it needs when it is issued, so a change
 * never touches an issued invoice.
 *
 * @property name `customer_name`.
 * @property origin whether the customer is established in Burundi.
 * @property personType whether the customer is a natural or a legal person.
 * @property tin `customer_TIN`, checked with the OBR, or null.
 * @property address `customer_address`; may be empty.
 * @property phone a phone number, kept in the application only; may be empty.
 * @property vatPayer `vat_customer_payer`: only a customer with a TIN can be registered for the VAT.
 */
data class CustomerDetails(
    val name: String,
    val origin: CustomerOrigin,
    val personType: TaxpayerType,
    val tin: VerifiedTin?,
    val address: String,
    val phone: String,
    val vatPayer: Boolean,
)

/**
 * A customer (UC-B05). Instances come from the use cases or from the storage.
 *
 * @property id identifier in the local storage.
 * @property details what can change.
 * @property archived whether the customer is hidden from the sale. It is never deleted.
 * @property counter whether this is the counter customer, used for the anonymous sales: there is
 * exactly one, it has no TIN, only its name changes and it is never archived.
 */
data class Customer(
    val id: CustomerId,
    val details: CustomerDetails,
    val archived: Boolean = false,
    val counter: Boolean = false,
) {
    /** What the counter customer starts with. */
    companion object {
        /** Name of the counter customer until the user changes it. */
        const val DEFAULT_COUNTER_NAME = "Client comptoir"

        /**
         * @param name the name written on the anonymous invoices.
         * @return the details of the counter customer.
         */
        fun counterDetails(name: String = DEFAULT_COUNTER_NAME): CustomerDetails = CustomerDetails(
            name = name,
            origin = CustomerOrigin.LOCAL,
            personType = TaxpayerType.NATURAL_PERSON,
            tin = null,
            address = "",
            phone = "",
            vatPayer = false,
        )
    }
}

/** What the local search of the customers looks in. */
object CustomerSearchText {
    /**
     * @param details a customer.
     * @return its name, its TIN and its phone number, normalized by [SearchText.normalize].
     */
    fun of(details: CustomerDetails): String =
        SearchText.normalize("${details.name} ${details.tin?.number.orEmpty()} ${details.phone}")
}

/** Storage of the customers. */
interface CustomerRepository {
    /**
     * @param id an identifier.
     * @return the customer, or null if there is none.
     */
    suspend fun find(id: CustomerId): Customer?

    /**
     * @param tin a TIN.
     * @return the customer with that TIN, archived or not, or null.
     */
    suspend fun findByTin(tin: String): Customer?

    /** @return the counter customer, created with [Customer.counterDetails] the first time. */
    suspend fun counter(): Customer

    /**
     * @param details the new customer.
     * @return the stored customer.
     */
    suspend fun create(details: CustomerDetails): Customer

    /**
     * @param id the customer.
     * @param details what replaces its details.
     */
    suspend fun update(id: CustomerId, details: CustomerDetails)

    /**
     * @param id the customer.
     * @param archived true to archive it, false to bring it back.
     */
    suspend fun setArchived(id: CustomerId, archived: Boolean)

    /**
     * @param query what the user typed; an empty text finds every customer.
     * @param archived whether to look among the archived customers instead of the active ones.
     * @param limit how many customers to return at most.
     * @return the customers whose [CustomerSearchText] contains [query], sorted by name. The counter
     * customer is never among them.
     */
    suspend fun search(query: String, archived: Boolean, limit: Int): List<Customer>

    /**
     * @param archived whether to count the archived customers instead of the active ones.
     * @return how many customers there are, without the counter customer.
     */
    suspend fun count(archived: Boolean): Int
}
