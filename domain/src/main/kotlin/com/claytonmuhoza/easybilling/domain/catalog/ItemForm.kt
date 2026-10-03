package com.claytonmuhoza.easybilling.domain.catalog

import com.claytonmuhoza.easybilling.domain.configuration.TaxDefinition
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money

/** A broken rule of an item. */
enum class ItemProblem {
    /** The code is empty. */
    CODE_MISSING,

    /** The code is longer than the size of [ItemField.CODE]. */
    CODE_TOO_LONG,

    /** Several devices invoice for the business and the code does not start with the device code. */
    CODE_DEVICE_PREFIX_MISSING,

    /** Another item, archived or not, already has this code. */
    CODE_TAKEN,

    /** The designation is empty. */
    DESIGNATION_MISSING,

    /** The designation is longer than the size of [ItemField.DESIGNATION]. */
    DESIGNATION_TOO_LONG,

    /** The unit is empty. */
    UNIT_MISSING,

    /** The unit is longer than the size of [ItemField.UNIT]. */
    UNIT_TOO_LONG,

    /** No price is typed. */
    PRICE_MISSING,

    /** The price is not a number above zero with two decimals at most. */
    PRICE_INVALID,

    /** It is not said whether the price includes the taxes. */
    PRICE_MODE_MISSING,

    /** It is not said whether the item is kept in stock. */
    STOCK_CHOICE_MISSING,

    /** The business is registered for the VAT and the item has none. */
    VAT_MISSING,

    /** The barcode is longer than [ItemForm.MAX_BARCODE_LENGTH]. */
    BARCODE_TOO_LONG,

    /** Another item, archived or not, already has this barcode. */
    BARCODE_TAKEN,

    /** A chosen tax does not exist, is of another kind or is archived. */
    TAX_NOT_AVAILABLE,
}

/**
 * What the rules of an item depend on, besides the item itself.
 *
 * @property registrations the taxes the business is registered for; none while no tax profile is saved.
 * @property devicePrefix the code of this device when several devices invoice for the business:
 * every code then starts with it, so two devices never give the same code. Null otherwise.
 * @property taxes the taxes that are defined.
 */
data class ItemRules(
    val registrations: TaxRegistrations,
    val devicePrefix: String?,
    val taxes: List<TaxDefinition>,
) {
    /** What a code must start with, or an empty text. */
    val codePrefix: String get() = devicePrefix?.let { "$it$PREFIX_SEPARATOR" }.orEmpty()

    private companion object {
        const val PREFIX_SEPARATOR = "-"
    }
}

/** Result of [ItemForm.check]. */
sealed interface ItemCheck {
    /**
     * Every rule holds.
     *
     * @property code the code, trimmed and in capitals.
     * @property details the rest of the item.
     */
    data class Valid(val code: String, val details: ItemDetails) : ItemCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<ItemProblem>) : ItemCheck
}

/**
 * An item as typed by the user, not checked yet.
 *
 * @property code its code; ignored for an existing item.
 * @property designation its designation.
 * @property unit its measurement unit.
 * @property price its unit price in Burundian francs, as typed; a comma is a decimal point.
 * @property priceMode whether the price includes the taxes, or null while not chosen.
 * @property taxes the tax chosen for each kind.
 * @property stockTracked whether the item is kept in stock, or null while not chosen.
 * @property barcode the barcode printed on the item; empty for none.
 */
data class ItemForm(
    val code: String = "",
    val designation: String = "",
    val unit: String = "",
    val price: String = "",
    val priceMode: PriceMode? = null,
    val taxes: Map<TaxKind, TaxId> = emptyMap(),
    val stockTracked: Boolean? = null,
    val barcode: String = "",
) {
    /**
     * @param rules what the rules depend on.
     * @param existing the item being changed, whose code is kept, or null for a new item.
     * @return the item, or every broken rule.
     */
    fun check(rules: ItemRules, existing: Item? = null): ItemCheck {
        val problems = mutableSetOf<ItemProblem>()
        val checkedCode = existing?.code ?: code.trim().uppercase().also { problems += codeProblems(it, rules) }
        val checkedDesignation = designation.trim()
        problems += textProblem(checkedDesignation, ItemField.DESIGNATION)
        val checkedUnit = unit.trim()
        problems += textProblem(checkedUnit, ItemField.UNIT)
        val checkedPrice = price(problems)
        if (priceMode == null) problems += ItemProblem.PRICE_MODE_MISSING
        if (stockTracked == null) problems += ItemProblem.STOCK_CHOICE_MISSING
        problems += taxProblems(rules)
        val checkedBarcode = barcode.trim().ifEmpty { null }
        if (barcode.trim().length > MAX_BARCODE_LENGTH) problems += ItemProblem.BARCODE_TOO_LONG

        val details = if (checkedPrice != null && priceMode != null && stockTracked != null) {
            ItemDetails(checkedDesignation, checkedUnit, checkedPrice, priceMode, taxes, stockTracked, checkedBarcode)
        } else {
            null
        }
        return if (details != null && problems.isEmpty()) {
            ItemCheck.Valid(checkedCode, details)
        } else {
            ItemCheck.Invalid(problems)
        }
    }

    private fun codeProblems(code: String, rules: ItemRules): Set<ItemProblem> = setOfNotNull(
        textProblem(code, ItemField.CODE).singleOrNull(),
        ItemProblem.CODE_DEVICE_PREFIX_MISSING.takeIf { code.isNotEmpty() && !code.startsWith(rules.codePrefix) },
    )

    private fun textProblem(value: String, field: ItemField): Set<ItemProblem> = setOfNotNull(
        MISSING.getValue(field).takeIf { value.isEmpty() },
        TOO_LONG.getValue(field).takeIf { value.length > field.maxLength },
    )

    /** Reads [price]: a number above zero, two decimals at most. */
    private fun price(problems: MutableSet<ItemProblem>): Money? {
        val typed = price.trim().replace(',', '.')
        val number = typed.toBigDecimalOrNull()?.stripTrailingZeros()
        return when {
            typed.isEmpty() -> null.also { problems += ItemProblem.PRICE_MISSING }
            number == null || number.signum() <= 0 || number.scale() > MAX_DECIMALS ->
                null.also { problems += ItemProblem.PRICE_INVALID }
            else -> Money.of(number, Currency.BIF)
        }
    }

    private fun taxProblems(rules: ItemRules): Set<ItemProblem> {
        val available = taxes.all { (kind, id) ->
            rules.taxes.any { it.id == id && it.kind == kind && !it.archived }
        }
        return setOfNotNull(
            ItemProblem.TAX_NOT_AVAILABLE.takeIf { !available },
            ItemProblem.VAT_MISSING.takeIf { rules.registrations.vat && TaxKind.VAT !in taxes },
        )
    }

    /** Ways to start a form. */
    companion object {
        /** Longest barcode accepted (internal rule): its format is not checked. */
        const val MAX_BARCODE_LENGTH = 50

        private const val MAX_DECIMALS = 2

        private val MISSING = mapOf(
            ItemField.CODE to ItemProblem.CODE_MISSING,
            ItemField.DESIGNATION to ItemProblem.DESIGNATION_MISSING,
            ItemField.UNIT to ItemProblem.UNIT_MISSING,
        )
        private val TOO_LONG = mapOf(
            ItemField.CODE to ItemProblem.CODE_TOO_LONG,
            ItemField.DESIGNATION to ItemProblem.DESIGNATION_TOO_LONG,
            ItemField.UNIT to ItemProblem.UNIT_TOO_LONG,
        )

        /**
         * @param item a stored item.
         * @return the form that gives it back.
         */
        fun from(item: Item): ItemForm = ItemForm(
            code = item.code,
            designation = item.details.designation,
            unit = item.details.unit,
            price = item.details.price.toPlainString(),
            priceMode = item.details.priceMode,
            taxes = item.details.taxes,
            stockTracked = item.details.stockTracked,
            barcode = item.details.barcode.orEmpty(),
        )
    }
}
