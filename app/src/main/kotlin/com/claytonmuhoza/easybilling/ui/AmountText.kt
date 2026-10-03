package com.claytonmuhoza.easybilling.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.ResolverStyle

/** Most decimals ever shown: amounts are rounded to two (decision D08). */
private const val MAX_DECIMALS = 2

/**
 * @param number a number.
 * @return the number written for the language of the device, without useless zeros: `2,420.4`.
 */
@Composable
fun decimalText(number: BigDecimal): String {
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0])
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = MAX_DECIMALS
    return format.format(number)
}

/**
 * @param money an amount.
 * @return the amount and its currency, written for the language of the device: `2,420.4 BIF`.
 */
@Composable
fun amountText(money: Money): String = "${decimalText(money.amount)} ${money.currency}"

/**
 * @param date a day.
 * @return the day written for the language of the device.
 */
@Composable
fun dateText(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]).format(date)

/** How a day is typed in a field, whatever the language: `03/10/2026`. */
object DateInput {
    private val format: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    /**
     * @param date a day.
     * @return the day as typed in a field.
     */
    fun format(date: LocalDate): String = format.format(date)

    /**
     * @param text a day as typed.
     * @return the day, or null if [text] is not a real day written DD/MM/YYYY.
     */
    fun parse(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), format) }.getOrNull()

    /** @return the day of the business, in Bujumbura, as typed in a field. */
    fun today(time: TimeProvider): String = format(BusinessCalendar.today(time))
}
