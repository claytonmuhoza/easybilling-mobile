package com.claytonmuhoza.easybilling.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemProblem
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.ui.amountText

/** @return whether the price includes the taxes, in a few letters: `excl. taxes`, `incl. taxes`. */
@Composable
fun PriceMode.shortLabel(): String = stringResource(
    when (this) {
        PriceMode.EXCLUDING_TAXES -> R.string.item_price_excluding
        PriceMode.INCLUDING_TAXES -> R.string.item_price_including
    },
)

/** @return whether the price includes the taxes, in words. */
@Composable
fun PriceMode.label(): String = stringResource(
    when (this) {
        PriceMode.EXCLUDING_TAXES -> R.string.item_price_mode_excluding
        PriceMode.INCLUDING_TAXES -> R.string.item_price_mode_including
    },
)

/** @return whether an item is kept in stock, in words. */
@Composable
internal fun stockLabel(stockTracked: Boolean): String =
    stringResource(if (stockTracked) R.string.item_stock_tracked else R.string.item_stock_not_tracked)

/** @return the item in one line: `ART-0001 · 1,500 BIF excl. taxes / piece`. */
@Composable
fun Item.summary(): String =
    "$code · ${amountText(details.price)} ${details.priceMode.shortLabel()} / ${details.unit}"

/**
 * @param codePrefix what a code must start with when several devices invoice.
 * @return the message explaining the broken rule.
 */
@Composable
fun ItemProblem.message(codePrefix: String): String =
    // Only the message about the device code uses the prefix; the others ignore the argument.
    stringResource(PROBLEM_MESSAGES.getValue(this), codePrefix)

/** The message of each broken rule: a problem without a message fails the first time it is shown. */
internal val PROBLEM_MESSAGES = mapOf(
    ItemProblem.CODE_MISSING to R.string.item_problem_code_missing,
    ItemProblem.CODE_TOO_LONG to R.string.item_problem_code_too_long,
    ItemProblem.CODE_DEVICE_PREFIX_MISSING to R.string.item_problem_code_prefix,
    ItemProblem.CODE_TAKEN to R.string.item_problem_code_taken,
    ItemProblem.DESIGNATION_MISSING to R.string.item_problem_designation_missing,
    ItemProblem.DESIGNATION_TOO_LONG to R.string.item_problem_designation_too_long,
    ItemProblem.UNIT_MISSING to R.string.item_problem_unit_missing,
    ItemProblem.UNIT_TOO_LONG to R.string.item_problem_unit_too_long,
    ItemProblem.PRICE_MISSING to R.string.item_problem_price_missing,
    ItemProblem.PRICE_INVALID to R.string.item_problem_price_invalid,
    ItemProblem.PRICE_MODE_MISSING to R.string.item_problem_price_mode_missing,
    ItemProblem.STOCK_CHOICE_MISSING to R.string.item_problem_stock_missing,
    ItemProblem.VAT_MISSING to R.string.item_problem_vat_missing,
    ItemProblem.TAX_NOT_AVAILABLE to R.string.item_problem_tax_not_available,
    ItemProblem.BARCODE_TOO_LONG to R.string.item_problem_barcode_too_long,
    ItemProblem.BARCODE_TAKEN to R.string.item_problem_barcode_taken,
)

/** The problems shown under each field of an item. */
internal object ItemProblemFields {
    /** Shown under the code. */
    val code = setOf(
        ItemProblem.CODE_MISSING,
        ItemProblem.CODE_TOO_LONG,
        ItemProblem.CODE_DEVICE_PREFIX_MISSING,
        ItemProblem.CODE_TAKEN,
    )

    /** Shown under the designation. */
    val designation = setOf(ItemProblem.DESIGNATION_MISSING, ItemProblem.DESIGNATION_TOO_LONG)

    /** Shown under the unit. */
    val unit = setOf(ItemProblem.UNIT_MISSING, ItemProblem.UNIT_TOO_LONG)

    /** Shown under the barcode. */
    val barcode = setOf(ItemProblem.BARCODE_TOO_LONG, ItemProblem.BARCODE_TAKEN)

    /** Shown under the price. */
    val price = setOf(ItemProblem.PRICE_MISSING, ItemProblem.PRICE_INVALID)

    /** Shown under the price mode. */
    val priceMode = setOf(ItemProblem.PRICE_MODE_MISSING)

    /** Shown under the stock choice. */
    val stock = setOf(ItemProblem.STOCK_CHOICE_MISSING)

    /** Shown under the VAT. */
    val vat = setOf(ItemProblem.VAT_MISSING)

    /** Shown under the taxes. */
    val taxes = setOf(ItemProblem.TAX_NOT_AVAILABLE)
}
