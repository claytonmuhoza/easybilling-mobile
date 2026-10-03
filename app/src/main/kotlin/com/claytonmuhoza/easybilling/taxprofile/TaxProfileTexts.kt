package com.claytonmuhoza.easybilling.taxprofile

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType

/** @return the label of the field, as shown on screen. */
@Composable
fun TaxProfileField.label(): String = stringResource(
    when (this) {
        TaxProfileField.NAME -> R.string.tax_profile_field_name
        TaxProfileField.TIN -> R.string.tax_profile_field_tin
        TaxProfileField.TRADE_NUMBER -> R.string.tax_profile_field_trade_number
        TaxProfileField.POSTAL_NUMBER -> R.string.tax_profile_field_postal_number
        TaxProfileField.PHONE_NUMBER -> R.string.tax_profile_field_phone_number
        TaxProfileField.PROVINCE -> R.string.tax_profile_field_province
        TaxProfileField.COMMUNE -> R.string.tax_profile_field_commune
        TaxProfileField.QUARTIER -> R.string.tax_profile_field_quartier
        TaxProfileField.AVENUE -> R.string.tax_profile_field_avenue
        TaxProfileField.RUE -> R.string.tax_profile_field_rue
        TaxProfileField.ADDRESS_NUMBER -> R.string.tax_profile_field_address_number
        TaxProfileField.ACTIVITY_SECTOR -> R.string.tax_profile_field_activity_sector
        TaxProfileField.LEGAL_FORM -> R.string.tax_profile_field_legal_form
    },
)

/** @return the name of the kind of taxpayer. */
@Composable
fun TaxpayerType.label(): String = stringResource(
    when (this) {
        TaxpayerType.NATURAL_PERSON -> R.string.tax_profile_natural_person
        TaxpayerType.LEGAL_PERSON -> R.string.tax_profile_legal_person
    },
)

/** The fields of each section of the profile, in display order. */
internal object TaxProfileSections {
    /** Who the taxpayer is. */
    val identity = listOf(
        TaxProfileField.NAME,
        TaxProfileField.TIN,
        TaxProfileField.TRADE_NUMBER,
        TaxProfileField.PHONE_NUMBER,
        TaxProfileField.POSTAL_NUMBER,
    )

    /** Where the taxpayer is. */
    val address = listOf(
        TaxProfileField.PROVINCE,
        TaxProfileField.COMMUNE,
        TaxProfileField.QUARTIER,
        TaxProfileField.AVENUE,
        TaxProfileField.RUE,
        TaxProfileField.ADDRESS_NUMBER,
    )

    /** How the taxpayer is taxed, after the tax office and the registrations. */
    val activity = listOf(TaxProfileField.ACTIVITY_SECTOR, TaxProfileField.LEGAL_FORM)
}
