package com.claytonmuhoza.easybilling.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Time zone of every date shown, whatever the setting of the device: the time of the business
 * (`docs/architecture.md` § 7.5).
 */
private val BusinessZone: ZoneId = ZoneId.of("Africa/Bujumbura")

/**
 * @param instant a moment.
 * @return the date and time of [instant] in Bujumbura, written for the language of the device.
 */
@Composable
fun dateTimeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(BusinessZone)
            .format(instant)
    }
}
