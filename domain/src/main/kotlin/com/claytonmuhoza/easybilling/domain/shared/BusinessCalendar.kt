package com.claytonmuhoza.easybilling.domain.shared

import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar of the business: every date is read in Bujumbura, whatever the setting of the
 * device (`docs/architecture.md` § 7.5).
 */
object BusinessCalendar {
    /** Time zone of the business. */
    val ZONE: ZoneId = ZoneId.of("Africa/Bujumbura")

    /**
     * @param time source of the current time.
     * @return the current day in Bujumbura.
     */
    fun today(time: TimeProvider): LocalDate = time.now().atZone(ZONE).toLocalDate()
}
