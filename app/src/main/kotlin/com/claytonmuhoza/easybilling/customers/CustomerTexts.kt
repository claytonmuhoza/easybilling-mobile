package com.claytonmuhoza.easybilling.customers

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.CustomerOrigin
import com.claytonmuhoza.easybilling.domain.customer.CustomerProblem
import com.claytonmuhoza.easybilling.domain.customer.TinCheckFailure

/** @return whether the customer is local or foreign, in words. */
@Composable
fun CustomerOrigin.label(): String = stringResource(
    when (this) {
        CustomerOrigin.LOCAL -> R.string.customer_origin_local
        CustomerOrigin.FOREIGN -> R.string.customer_origin_foreign
    },
)

/** @return whether the customer is a natural or a legal person, in words. */
@Composable
internal fun TaxpayerType.customerLabel(): String = stringResource(
    when (this) {
        TaxpayerType.NATURAL_PERSON -> R.string.tax_profile_natural_person
        TaxpayerType.LEGAL_PERSON -> R.string.tax_profile_legal_person
    },
)

/** @return the customer in one line: `Local · Legal person · TIN 4000202020 · VAT payer`. */
@Composable
fun Customer.summary(): String = listOfNotNull(
    details.origin.label(),
    details.personType.customerLabel(),
    details.tin?.let { stringResource(R.string.customer_tin_summary, it.number) },
    if (details.vatPayer) stringResource(R.string.customer_vat_payer_short) else null,
).joinToString(" · ")

/** @return the message explaining the broken rule. */
@Composable
fun CustomerProblem.message(): String = stringResource(CUSTOMER_PROBLEM_MESSAGES.getValue(this))

/** The message of each broken rule: a problem without a message fails the first time it is shown. */
internal val CUSTOMER_PROBLEM_MESSAGES = mapOf(
    CustomerProblem.NAME_MISSING to R.string.customer_problem_name_missing,
    CustomerProblem.NAME_TOO_LONG to R.string.customer_problem_name_too_long,
    CustomerProblem.ORIGIN_MISSING to R.string.customer_problem_origin_missing,
    CustomerProblem.PERSON_TYPE_MISSING to R.string.customer_problem_person_type_missing,
    CustomerProblem.TIN_MISSING to R.string.customer_problem_tin_missing,
    CustomerProblem.TIN_TOO_LONG to R.string.customer_problem_tin_too_long,
    CustomerProblem.TIN_TAKEN to R.string.customer_problem_tin_taken,
    CustomerProblem.ADDRESS_TOO_LONG to R.string.customer_problem_address_too_long,
    CustomerProblem.PHONE_TOO_LONG to R.string.customer_problem_phone_too_long,
    CustomerProblem.VAT_PAYER_WITHOUT_TIN to R.string.customer_problem_vat_payer_without_tin,
)

/** The message of each reason a TIN could not be checked. */
internal val TIN_FAILURE_MESSAGES = mapOf(
    TinCheckFailure.NOT_CONFIGURED to R.string.customer_tin_failure_not_configured,
    TinCheckFailure.UNREACHABLE to R.string.customer_tin_failure_unreachable,
    TinCheckFailure.SERVER_ERROR to R.string.customer_tin_failure_server_error,
    TinCheckFailure.CERTIFICATE_REJECTED to R.string.customer_tin_failure_certificate,
    TinCheckFailure.LOGIN_REFUSED to R.string.customer_tin_failure_login_refused,
    TinCheckFailure.INVALID_RESPONSE to R.string.customer_tin_failure_invalid_response,
    TinCheckFailure.REFUSED to R.string.customer_tin_failure_refused,
)

/**
 * @param obrMessage the message of the OBR, when it gave one.
 * @return the message saying why the TIN could not be checked.
 */
@Composable
fun TinCheckFailure.message(obrMessage: String): String =
    // Only the refusal of the OBR uses its message; the others ignore the argument.
    stringResource(TIN_FAILURE_MESSAGES.getValue(this), obrMessage)

/** The problems shown under each field of a customer. */
internal object CustomerProblemFields {
    /** Shown under the name. */
    val name = setOf(CustomerProblem.NAME_MISSING, CustomerProblem.NAME_TOO_LONG)

    /** Shown under the choice between local and foreign. */
    val origin = setOf(CustomerProblem.ORIGIN_MISSING)

    /** Shown under the choice between natural and legal person. */
    val personType = setOf(CustomerProblem.PERSON_TYPE_MISSING)

    /** Shown under the TIN. */
    val tin = setOf(CustomerProblem.TIN_MISSING, CustomerProblem.TIN_TOO_LONG, CustomerProblem.TIN_TAKEN)

    /** Shown under the address. */
    val address = setOf(CustomerProblem.ADDRESS_TOO_LONG)

    /** Shown under the phone number. */
    val phone = setOf(CustomerProblem.PHONE_TOO_LONG)

    /** Shown under the VAT switch. */
    val vatPayer = setOf(CustomerProblem.VAT_PAYER_WITHOUT_TIN)
}
