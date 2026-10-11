package com.claytonmuhoza.easybilling.stock

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.stock.MovementDirection
import com.claytonmuhoza.easybilling.domain.stock.MovementProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.stock.StockProblem
import com.claytonmuhoza.easybilling.ui.decimalText
import java.math.BigDecimal

/** The name of each type of movement: a type without a name fails the first time it is shown. */
internal val TYPE_LABELS = mapOf(
    MovementType.EN to R.string.stock_type_en,
    MovementType.ER to R.string.stock_type_er,
    MovementType.EI to R.string.stock_type_ei,
    MovementType.EAJ to R.string.stock_type_eaj,
    MovementType.ET to R.string.stock_type_et,
    MovementType.EAU to R.string.stock_type_eau,
    MovementType.SN to R.string.stock_type_sn,
    MovementType.SP to R.string.stock_type_sp,
    MovementType.SV to R.string.stock_type_sv,
    MovementType.SD to R.string.stock_type_sd,
    MovementType.SC to R.string.stock_type_sc,
    MovementType.SAJ to R.string.stock_type_saj,
    MovementType.ST to R.string.stock_type_st,
    MovementType.SAU to R.string.stock_type_sau,
)

/** The message of each broken rule: a problem without a message fails the first time it is shown. */
internal val MOVEMENT_PROBLEM_MESSAGES = mapOf(
    MovementProblem.TYPE_MISSING to R.string.stock_problem_type,
    MovementProblem.QUANTITY_INVALID to R.string.stock_problem_quantity,
    MovementProblem.COST_INVALID to R.string.stock_problem_cost,
    MovementProblem.DESCRIPTION_REQUIRED to R.string.stock_problem_description_required,
    MovementProblem.DESCRIPTION_TOO_LONG to R.string.stock_problem_description_too_long,
)

/** @return the type of movement in words: `Local purchase`, `Loss`. */
@Composable
fun MovementType.label(): String = stringResource(TYPE_LABELS.getValue(this))

/** @return the message explaining the broken rule. */
@Composable
fun MovementProblem.message(): String = stringResource(MOVEMENT_PROBLEM_MESSAGES.getValue(this))

/** @return why the item cannot move, in words. */
@Composable
fun StockProblem.message(): String = stringResource(
    when (this) {
        StockProblem.ITEM_NOT_FOUND -> R.string.stock_problem_not_found
        StockProblem.ITEM_NOT_TRACKED -> R.string.stock_problem_not_tracked
        StockProblem.ITEM_ARCHIVED -> R.string.stock_problem_archived
    },
)

/** @return where the movement stands with the OBR, in words. */
@Composable
fun MovementStatus.label(): String = stringResource(
    when (this) {
        MovementStatus.Pending -> R.string.stock_status_pending
        MovementStatus.Declared -> R.string.stock_status_declared
        is MovementStatus.Rejected -> R.string.stock_status_rejected
        MovementStatus.ToConfirm -> R.string.stock_status_to_confirm
        MovementStatus.Voided -> R.string.stock_status_voided
    },
)

/** @return the colour of the status. */
fun MovementStatus.tone(): StatusTone = when (this) {
    MovementStatus.Pending -> StatusTone.PENDING
    MovementStatus.Declared -> StatusTone.SUCCESS
    is MovementStatus.Rejected, MovementStatus.ToConfirm -> StatusTone.ERROR
    MovementStatus.Voided -> StatusTone.NEUTRAL
}

/**
 * @param quantity a quantity.
 * @param unit the measurement unit of the item.
 * @return the quantity with its unit: `12.5 piece`.
 */
@Composable
fun quantityText(quantity: BigDecimal, unit: String): String =
    stringResource(R.string.stock_quantity, decimalText(quantity), unit)

/** @return what the movement does to the stock, with its sign: `+12.5 piece`, `−2 piece`. */
@Composable
fun StockMovementContent.signedQuantityText(): String = stringResource(
    if (type.direction == MovementDirection.ENTRY) R.string.stock_movement_entry else R.string.stock_movement_exit,
    quantityText(quantity.value, unit),
)
