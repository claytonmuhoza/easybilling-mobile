package com.claytonmuhoza.easybilling.data.local.configuration

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/** Row of the `tax_profile_versions` table. The number is the primary key, so it cannot repeat. */
@Entity(tableName = "tax_profile_versions")
internal class TaxProfileVersionEntity(
    @PrimaryKey val number: Int,
    val taxpayerType: String,
    val name: String,
    val tin: String,
    val tradeNumber: String,
    val postalNumber: String,
    val phoneNumber: String,
    val province: String,
    val commune: String,
    val quartier: String,
    val avenue: String,
    val rue: String,
    val addressNumber: String,
    val vatTaxpayer: Boolean,
    val consumptionTaxpayer: Boolean,
    val flatRateWithholdingTaxpayer: Boolean,
    val fiscalCenter: String,
    val activitySector: String,
    val legalForm: String,
    val savedAtMillis: Long,
    val savedById: Long,
)

internal fun TaxProfileVersionEntity.toDomain() = TaxProfileVersion(
    number = number,
    profile = TaxProfile(
        taxpayerType = TaxpayerType.valueOf(taxpayerType),
        name = name,
        tin = tin,
        tradeNumber = tradeNumber,
        postalNumber = postalNumber,
        phoneNumber = phoneNumber,
        address = Address(province, commune, quartier, avenue, rue, addressNumber),
        taxes = TaxRegistrations(vatTaxpayer, consumptionTaxpayer, flatRateWithholdingTaxpayer),
        fiscalCenter = FiscalCenter.valueOf(fiscalCenter),
        activitySector = activitySector,
        legalForm = legalForm,
    ),
    savedAt = Instant.ofEpochMilli(savedAtMillis),
    savedBy = UserId(savedById),
)

internal fun TaxProfileVersion.toEntity() = with(profile) {
    TaxProfileVersionEntity(
        number = number,
        taxpayerType = taxpayerType.name,
        name = name,
        tin = tin,
        tradeNumber = tradeNumber,
        postalNumber = postalNumber,
        phoneNumber = phoneNumber,
        province = address.province,
        commune = address.commune,
        quartier = address.quartier,
        avenue = address.avenue,
        rue = address.rue,
        addressNumber = address.number,
        vatTaxpayer = taxes.vat,
        consumptionTaxpayer = taxes.consumptionTax,
        flatRateWithholdingTaxpayer = taxes.flatRateWithholding,
        fiscalCenter = fiscalCenter.name,
        activitySector = activitySector,
        legalForm = legalForm,
        savedAtMillis = savedAt.toEpochMilli(),
        savedById = savedBy.value,
    )
}
