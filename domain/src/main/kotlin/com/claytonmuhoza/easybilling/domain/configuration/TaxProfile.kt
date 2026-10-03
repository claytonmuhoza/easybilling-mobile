package com.claytonmuhoza.easybilling.domain.configuration

/**
 * Kind of taxpayer (`tp_type`).
 *
 * @property code the value sent to the OBR.
 */
enum class TaxpayerType(val code: String) {
    /** A natural person: `tp_name` holds the first and last names. */
    NATURAL_PERSON("1"),

    /** A legal person (company): `tp_name` holds the trade name. */
    LEGAL_PERSON("2"),
}

/**
 * Tax office that manages the taxpayer (`tp_fiscal_center`).
 *
 * @property code the value sent to the OBR.
 */
enum class FiscalCenter(val code: String) {
    /** Direction des Grands Contribuables: large taxpayers. */
    DGC("DGC"),

    /** Direction des Moyens Contribuables: medium taxpayers. */
    DMC("DMC"),

    /** Direction des Petits et Micro Contribuables: small and micro taxpayers. */
    DPMC("DPMC"),
}

/**
 * Taxes the taxpayer is registered for. Each one is sent to the OBR as `1` (registered) or `0`.
 *
 * @property vat value added tax (TVA, `vat_taxpayer`).
 * @property consumptionTax consumption tax (TC, `ct_taxpayer`).
 * @property flatRateWithholding prélèvement forfaitaire libératoire (PFL, `tl_taxpayer`).
 */
data class TaxRegistrations(
    val vat: Boolean = false,
    val consumptionTax: Boolean = false,
    val flatRateWithholding: Boolean = false,
)

/**
 * Address of the taxpayer. An empty text means "not given", as the OBR expects `""`, never null.
 *
 * @property province `tp_address_province`.
 * @property commune `tp_address_commune`.
 * @property quartier `tp_address_quartier`: the quartier in town, the colline elsewhere.
 * @property avenue `tp_address_avenue`.
 * @property rue `tp_address_rue`, optional.
 * @property number `tp_address_number`, optional.
 */
data class Address(
    val province: String,
    val commune: String,
    val quartier: String,
    val avenue: String,
    val rue: String,
    val number: String,
)

/**
 * The tax profile of the business: the header of every invoice sent to the OBR (UC-A02).
 *
 * Instances only come from [TaxProfileForm.check], so every rule of [TaxProfileField] holds. An
 * empty text means "not given".
 *
 * @property taxpayerType `tp_type`.
 * @property name `tp_name`.
 * @property tin `tp_TIN`, the tax identification number (NIF).
 * @property tradeNumber `tp_trade_number`, the trade register number.
 * @property postalNumber `tp_postal_number`, optional.
 * @property phoneNumber `tp_phone_number`.
 * @property address the address of the taxpayer.
 * @property taxes `vat_taxpayer`, `ct_taxpayer` and `tl_taxpayer`.
 * @property fiscalCenter `tp_fiscal_center`.
 * @property activitySector `tp_activity_sector`.
 * @property legalForm `tp_legal_form`.
 */
data class TaxProfile(
    val taxpayerType: TaxpayerType,
    val name: String,
    val tin: String,
    val tradeNumber: String,
    val postalNumber: String,
    val phoneNumber: String,
    val address: Address,
    val taxes: TaxRegistrations,
    val fiscalCenter: FiscalCenter,
    val activitySector: String,
    val legalForm: String,
)
