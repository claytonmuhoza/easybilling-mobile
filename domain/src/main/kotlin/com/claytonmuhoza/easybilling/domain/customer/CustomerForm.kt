package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType

/** A broken rule of a customer. */
enum class CustomerProblem {
    /** The name is empty and there is no TIN whose owner the OBR could name. */
    NAME_MISSING,

    /** The name is longer than the size of [CustomerField.NAME]. */
    NAME_TOO_LONG,

    /** It is not said whether the customer is local or foreign. */
    ORIGIN_MISSING,

    /** It is not said whether the customer is a natural or a legal person. */
    PERSON_TYPE_MISSING,

    /** A local legal person has no TIN. */
    TIN_MISSING,

    /** The TIN is longer than the size of [CustomerField.TIN]. */
    TIN_TOO_LONG,

    /** Another customer already has this TIN. */
    TIN_TAKEN,

    /** The address is longer than the size of [CustomerField.ADDRESS]. */
    ADDRESS_TOO_LONG,

    /** The phone number is longer than [CustomerForm.MAX_PHONE_LENGTH]. */
    PHONE_TOO_LONG,

    /** The customer is said to be registered for the VAT and has no TIN. */
    VAT_PAYER_WITHOUT_TIN,
}

/**
 * A customer whose rules hold, before its TIN is checked with the OBR.
 *
 * @property name its name; empty when the name of the owner of the TIN is to be taken.
 * @property origin whether it is established in Burundi.
 * @property personType whether it is a natural or a legal person.
 * @property tin its TIN, not checked yet, or null.
 * @property address its address; may be empty.
 * @property phone its phone number; may be empty.
 * @property vatPayer whether it is registered for the VAT.
 */
data class CustomerDraft(
    val name: String,
    val origin: CustomerOrigin,
    val personType: TaxpayerType,
    val tin: String?,
    val address: String,
    val phone: String,
    val vatPayer: Boolean,
) {
    /**
     * @param verified the TIN as the OBR recognised it, or null for a customer without a TIN.
     * @return the customer to store. An empty name is replaced by the name the OBR gave.
     */
    fun toDetails(verified: VerifiedTin?): CustomerDetails = CustomerDetails(
        name = name.ifEmpty { verified?.obrName.orEmpty().take(CustomerField.NAME.maxLength) },
        origin = origin,
        personType = personType,
        tin = verified,
        address = address,
        phone = phone,
        vatPayer = vatPayer,
    )
}

/** Result of [CustomerForm.check]. */
sealed interface CustomerCheck {
    /** Every rule holds. */
    data class Valid(val draft: CustomerDraft) : CustomerCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<CustomerProblem>) : CustomerCheck
}

/**
 * A customer as typed by the user, not checked yet.
 *
 * @property name its name; may be left empty with a TIN, the OBR then gives it.
 * @property origin local or foreign, or null while not chosen.
 * @property personType natural or legal person, or null while not chosen.
 * @property tin its TIN; ignored for a foreign customer.
 * @property address its address.
 * @property phone its phone number.
 * @property vatPayer whether it is registered for the VAT.
 */
data class CustomerForm(
    val name: String = "",
    val origin: CustomerOrigin? = null,
    val personType: TaxpayerType? = null,
    val tin: String = "",
    val address: String = "",
    val phone: String = "",
    val vatPayer: Boolean = false,
) {
    /**
     * @param counter whether the form is the one of the counter customer: only its name is read.
     * @return the customer, or every broken rule.
     */
    fun check(counter: Boolean = false): CustomerCheck {
        val checkedName = name.trim()
        val problems = mutableSetOf<CustomerProblem>()
        if (checkedName.length > CustomerField.NAME.maxLength) problems += CustomerProblem.NAME_TOO_LONG
        if (counter) {
            if (checkedName.isEmpty()) problems += CustomerProblem.NAME_MISSING
            return result(problems) { _, _ -> Customer.counterDetails(checkedName).toDraft() }
        }
        val checkedTin = tin.trim().takeIf { origin == CustomerOrigin.LOCAL && it.isNotEmpty() }
        val checkedAddress = address.trim()
        val checkedPhone = phone.trim()
        if (origin == null) problems += CustomerProblem.ORIGIN_MISSING
        if (personType == null) problems += CustomerProblem.PERSON_TYPE_MISSING
        if (checkedName.isEmpty() && checkedTin == null) problems += CustomerProblem.NAME_MISSING
        problems += tinProblems(checkedTin)
        if (checkedAddress.length > CustomerField.ADDRESS.maxLength) problems += CustomerProblem.ADDRESS_TOO_LONG
        if (checkedPhone.length > MAX_PHONE_LENGTH) problems += CustomerProblem.PHONE_TOO_LONG
        return result(problems) { checkedOrigin, checkedType ->
            CustomerDraft(checkedName, checkedOrigin, checkedType, checkedTin, checkedAddress, checkedPhone, vatPayer)
        }
    }

    private fun tinProblems(checkedTin: String?): Set<CustomerProblem> {
        val mandatory = origin == CustomerOrigin.LOCAL && personType == TaxpayerType.LEGAL_PERSON
        return setOfNotNull(
            CustomerProblem.TIN_MISSING.takeIf { mandatory && checkedTin == null },
            CustomerProblem.TIN_TOO_LONG.takeIf { checkedTin.orEmpty().length > CustomerField.TIN.maxLength },
            CustomerProblem.VAT_PAYER_WITHOUT_TIN.takeIf { vatPayer && checkedTin == null },
        )
    }

    private fun result(
        problems: Set<CustomerProblem>,
        draft: (CustomerOrigin, TaxpayerType) -> CustomerDraft,
    ): CustomerCheck = if (problems.isEmpty()) {
        CustomerCheck.Valid(draft(origin ?: CustomerOrigin.LOCAL, personType ?: TaxpayerType.NATURAL_PERSON))
    } else {
        CustomerCheck.Invalid(problems)
    }

    private fun CustomerDetails.toDraft() = CustomerDraft(name, origin, personType, null, address, phone, vatPayer)

    /** Ways to start a form. */
    companion object {
        /** Longest phone number accepted (internal rule): it is not sent to the OBR. */
        const val MAX_PHONE_LENGTH = 20

        /**
         * @param customer a stored customer.
         * @return the form that gives it back.
         */
        fun from(customer: Customer): CustomerForm = CustomerForm(
            name = customer.details.name,
            origin = customer.details.origin,
            personType = customer.details.personType,
            tin = customer.details.tin?.number.orEmpty(),
            address = customer.details.address,
            phone = customer.details.phone,
            vatPayer = customer.details.vatPayer,
        )
    }
}
