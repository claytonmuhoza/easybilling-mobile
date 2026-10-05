package com.claytonmuhoza.easybilling.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * @param instant a moment.
 * @return the date and time of [instant] in Bujumbura, whatever the setting of the device, written
 * for the language of the device.
 */
@Composable
fun dateTimeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(BusinessCalendar.ZONE)
            .format(instant)
    }
}

/**
 * @param instant a moment.
 * @return the time of [instant] in Bujumbura, whatever the setting of the device, written for the
 * language of the device.
 */
@Composable
fun timeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(BusinessCalendar.ZONE)
            .format(instant)
    }
}
