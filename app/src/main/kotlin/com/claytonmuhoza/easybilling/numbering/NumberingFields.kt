package com.claytonmuhoza.easybilling.numbering

import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.NumberingSettings

/**
 * How the year is written in a number.
 *
 * @property token the token of the template, empty for no year.
 */
enum class YearFormat(val token: String) {
    /** No year. */
    NONE(""),

    /** Two digits: `26`. */
    SHORT("{YY}"),

    /** Four digits: `2026`. */
    FULL("{YYYY}"),
}

/**
 * The numbering as composed on the screen, field by field: the user never types a template.
 * A number is made of the prefix, the device code, the type of document and the counter, then
 * the year: `[prefix][device][separator]type[separator]counter[year separator][year]`.
 *
 * @property multipleDevices whether other devices issue documents for the same NIF.
 * @property deviceCode the code of this device; only used with several devices.
 * @property prefix text written first, with its own separator if it needs one.
 * @property separator text between the device code, the type and the counter.
 * @property counterDigits number of digits of the counter.
 * @property year how the year is written.
 * @property yearSeparator text between the counter and the year.
 * @property reset when the counters start again.
 */
data class NumberingFields(
    val multipleDevices: Boolean = false,
    val deviceCode: String = "",
    val prefix: String = "",
    val separator: String = "-",
    val counterDigits: Int = DEFAULT_COUNTER_DIGITS,
    val year: YearFormat = YearFormat.FULL,
    val yearSeparator: String = "/",
    val reset: CounterReset = CounterReset.YEARLY,
) {
    /** The template the fields compose, for example `{TYPE}-{NNNNN}/{YYYY}`. */
    val template: String
        get() = buildString {
            append(prefix.trim())
            if (multipleDevices) append("{DEVICE}").append(separator)
            append("{TYPE}").append(separator)
            append("{").append("N".repeat(counterDigits)).append("}")
            if (year != YearFormat.NONE) append(yearSeparator).append(year.token)
        }

    /** @return the numbering to check and save. */
    fun toForm(): NumberingForm = NumberingForm(template, reset, multipleDevices, deviceCode)

    /** Choices offered on the screen. */
    companion object {
        /** Digits of the counter unless the administrator chooses otherwise. */
        const val DEFAULT_COUNTER_DIGITS = 5

        /** Longest prefix that can be typed. */
        const val MAX_PREFIX_LENGTH = 10

        /** The separators offered; the empty text means none. */
        val SEPARATORS = listOf("-", "/", ".", "_", "")

        /** The numbers of digits offered for the counter. */
        val COUNTER_DIGITS = listOf(4, 5, 6, 8)

        private val COMPOSED = Regex(
            """^(?<prefix>[A-Za-z0-9/_.-]*?)(?:\{DEVICE\}[-/._]?)?\{TYPE\}(?<separator>[-/._]?)\{(?<counter>N+)\}""" +
                """(?:(?<yearSeparator>[-/._]?)\{(?<year>YYYY|YY)\})?$""",
        )

        /**
         * @param settings a numbering saved from this screen.
         * @return the fields that compose it; the default ones for a template made elsewhere.
         */
        fun from(settings: NumberingSettings): NumberingFields {
            val groups = COMPOSED.matchEntire(settings.template)?.groups ?: return NumberingFields()
            val year = groups["year"]?.value
            return NumberingFields(
                multipleDevices = settings.multipleDevices,
                deviceCode = settings.deviceCode,
                prefix = groups["prefix"]?.value.orEmpty(),
                separator = groups["separator"]?.value.orEmpty(),
                counterDigits = groups["counter"]?.value.orEmpty().length,
                year = YearFormat.entries.first { it.token == year?.let { digits -> "{$digits}" }.orEmpty() },
                yearSeparator = if (year == null) "/" else groups["yearSeparator"]?.value.orEmpty(),
                reset = settings.reset,
            )
        }
    }
}
