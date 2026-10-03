package com.claytonmuhoza.easybilling.domain.configuration

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The taxes of an invoice line in the OBR specification.
 *
 * @property obrField name of the line field the tax fills.
 */
enum class TaxKind(val obrField: String) {
    /** Value added tax (TVA), always a percentage of the price without VAT. */
    VAT("vat"),

    /** Consumption tax (TC), added to the price without VAT. */
    CONSUMPTION_TAX("item_ct"),

    /** Prélèvement forfaitaire libératoire (PFL), added after the VAT. */
    FLAT_RATE_WITHHOLDING("item_tl"),

    /** Over the top tax (OTT): "behaves like the TC" (specification). */
    OTT("item_ott_tax"),

    /** Tax on value added services of electronic communications: "behaves like the TC". */
    TSCE("item_tsce_tax"),
}

/** What a percentage is computed on, in the formula of the OBR. */
enum class PercentageBase {
    /** The unit price times the quantity (PU × Q). */
    PRICE,

    /** The price without VAT (HTVA, `item_price_nvat`). */
    PRICE_WITHOUT_VAT,

    /** The price with VAT (TVAC, `item_price_wvat`). */
    PRICE_WITH_VAT,
}

/** How the amount of a tax is found for a line (decision D09). */
sealed interface TaxComputation {
    /**
     * A percentage.
     *
     * @property rate the rate in percent: `18` for 18 %.
     * @property base what the rate applies to.
     */
    data class Percentage(val rate: BigDecimal, val base: PercentageBase) : TaxComputation {
        override fun equals(other: Any?): Boolean =
            other is Percentage && rate.compareTo(other.rate) == 0 && base == other.base

        override fun hashCode(): Int = 31 * rate.stripTrailingZeros().hashCode() + base.hashCode()
    }

    /**
     * An amount for each unit sold, multiplied by the quantity.
     *
     * @property amount the amount per unit, in the currency of the line.
     */
    data class PerUnit(val amount: BigDecimal) : TaxComputation {
        override fun equals(other: Any?): Boolean = other is PerUnit && amount.compareTo(other.amount) == 0

        override fun hashCode(): Int = amount.stripTrailingZeros().hashCode()
    }

    /** An amount typed on each line when selling. */
    data object FreeAmount : TaxComputation
}

/** Identifier of a tax definition. */
@JvmInline
value class TaxId(val value: Long)

/**
 * A rate of a tax and the day it applies from. A rate is never changed: a new one is scheduled
 * from a later day, so an invoice always shows the rate of its day (UC-A07).
 *
 * @property validFrom first day the rate applies, in Bujumbura.
 * @property computation how the amount is found.
 */
data class TaxVersion(val validFrom: LocalDate, val computation: TaxComputation)

/**
 * A tax the administrator defined, for example "TVA 18 %" or "TC bière".
 *
 * @property id identifier of the tax.
 * @property kind which tax of the OBR it fills.
 * @property name name shown when choosing the tax.
 * @property versions its rates, each from its own day.
 * @property archived true once it may no longer be chosen; it stays for the past.
 */
data class TaxDefinition(
    val id: TaxId,
    val kind: TaxKind,
    val name: String,
    val versions: List<TaxVersion>,
    val archived: Boolean = false,
) {
    /** The rate starting last, possibly in the future. */
    val latest: TaxVersion get() = versions.maxBy { it.validFrom }

    /**
     * @param date a day.
     * @return the rate in force that day, or null if the tax did not apply yet.
     */
    fun computationAt(date: LocalDate): TaxComputation? =
        versions.filter { !it.validFrom.isAfter(date) }.maxByOrNull { it.validFrom }?.computation
}

/** Storage of the tax definitions. */
interface TaxRepository {
    /** @return every tax, archived ones included, in creation order. */
    suspend fun all(): List<TaxDefinition>

    /**
     * @param id identifier of a tax.
     * @return the tax, or null if there is none with that identifier.
     */
    suspend fun find(id: TaxId): TaxDefinition?

    /**
     * @param kind which tax of the OBR it fills.
     * @param name its name.
     * @param version its first rate.
     * @return the stored tax, with its identifier.
     */
    suspend fun create(kind: TaxKind, name: String, version: TaxVersion): TaxDefinition

    /**
     * @param id identifier of a tax.
     * @param version a rate to add.
     */
    suspend fun addVersion(id: TaxId, version: TaxVersion)

    /**
     * @param id identifier of a tax.
     * @param archived whether it may no longer be chosen.
     */
    suspend fun setArchived(id: TaxId, archived: Boolean)
}
