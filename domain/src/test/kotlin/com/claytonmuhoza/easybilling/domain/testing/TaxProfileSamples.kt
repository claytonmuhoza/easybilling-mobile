package com.claytonmuhoza.easybilling.domain.testing

import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileField
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileForm
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType

/** A complete, valid tax profile form, made up for the tests. */
object TaxProfileSamples {
    fun form(): TaxProfileForm = TaxProfileForm(
        taxpayerType = TaxpayerType.LEGAL_PERSON,
        texts = mapOf(
            TaxProfileField.NAME to "Boutique Umuco SPRL",
            TaxProfileField.TIN to "4000000001",
            TaxProfileField.TRADE_NUMBER to "RC 12345",
            TaxProfileField.PHONE_NUMBER to "+257 22 22 22 22",
            TaxProfileField.PROVINCE to "Bujumbura Mairie",
            TaxProfileField.COMMUNE to "Mukaza",
            TaxProfileField.QUARTIER to "Rohero",
            TaxProfileField.AVENUE to "Avenue de l'Industrie",
            TaxProfileField.ACTIVITY_SECTOR to "Commerce general",
            TaxProfileField.LEGAL_FORM to "SPRL",
        ),
        taxes = TaxRegistrations(vat = true, consumptionTax = false, flatRateWithholding = false),
        fiscalCenter = FiscalCenter.DMC,
    )
}
