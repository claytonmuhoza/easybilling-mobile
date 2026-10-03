package com.claytonmuhoza.easybilling.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.claytonmuhoza.easybilling.core.designsystem.component.AmountText
import com.claytonmuhoza.easybilling.domain.shared.Money

/**
 * An amount with its currency smaller next to it, in tabular figures. Its text is the one of
 * [amountText]: `2,420.4 BIF`.
 *
 * @param money the amount.
 * @param modifier modifier applied to the text.
 * @param style style of the number.
 * @param color colour of the text.
 * @param fontWeight weight of the text, or null for the one of [style].
 */
@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
) {
    AmountText(decimalText(money.amount), money.currency.name, modifier, style, color, fontWeight)
}
