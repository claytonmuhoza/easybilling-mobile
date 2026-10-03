package com.claytonmuhoza.easybilling.domain.configuration

/** A broken rule of the numbering. */
enum class NumberingProblem {
    /** The template has no `{TYPE}`: two types of document could get the same number. */
    TYPE_MISSING,

    /** The template has no counter. */
    COUNTER_MISSING,

    /** The counter has more than [NumberingForm.MAX_COUNTER_DIGITS] digits. */
    COUNTER_DIGITS,

    /** The counters start again every year but the template has no year. */
    YEAR_MISSING,

    /** Several devices issue documents but the template has no `{DEVICE}`. */
    DEVICE_MISSING,

    /** The device code is needed and is not 1 to 4 capital letters or digits. */
    DEVICE_CODE_INVALID,

    /** A token appears more than once. */
    TOKEN_REPEATED,

    /** Something between braces is not a token, or a brace is alone. */
    UNKNOWN_TOKEN,

    /** The text has something else than letters, digits and `-` `/` `_` `.`. */
    INVALID_CHARACTER,

    /** A number could exceed [NumberingForm.MAX_NUMBER_LENGTH] characters. */
    TOO_LONG,

    /** A number could not fit in the identifier of the invoice. */
    IDENTIFIER_TOO_LONG,
}

/** Result of [NumberingForm.check]. */
sealed interface NumberingCheck {
    /** Every rule holds. */
    data class Valid(val settings: NumberingSettings) : NumberingCheck

    /** Some rules are broken; every problem is reported at once. */
    data class Invalid(val problems: Set<NumberingProblem>) : NumberingCheck
}

/**
 * The numbering as typed by the user, not checked yet.
 *
 * @property template the template of the numbers.
 * @property reset when the counters start again.
 * @property multipleDevices whether other devices issue documents for the same NIF.
 * @property deviceCode the code of this device; needed with several devices or with `{DEVICE}`.
 */
data class NumberingForm(
    val template: String = DEFAULT_TEMPLATE,
    val reset: CounterReset = CounterReset.YEARLY,
    val multipleDevices: Boolean = false,
    val deviceCode: String = "",
) {
    /**
     * @param identifierRoom the characters left for the number in the identifier of the invoice
     * (`InvoiceIdentifier.roomForNumber`), or null if it is not known yet.
     * @return the numbering, or every broken rule.
     */
    fun check(identifierRoom: Int?): NumberingCheck {
        val parts = NumberTemplate.parse(template)
        val code = deviceCode.trim().uppercase()
        val codeNeeded = multipleDevices || NumberPart.Device in parts
        val settings = NumberingSettings(template, reset, multipleDevices, if (codeNeeded) code else "")

        val problems = templateProblems(parts) + lengthProblems(settings.maxLength, identifierRoom) +
            setOfNotNull(NumberingProblem.DEVICE_CODE_INVALID.takeIf { codeNeeded && !DEVICE_CODE.matches(code) })
        return if (problems.isEmpty()) NumberingCheck.Valid(settings) else NumberingCheck.Invalid(problems)
    }

    private fun templateProblems(parts: List<NumberPart>): Set<NumberingProblem> {
        val counters = parts.filterIsInstance<NumberPart.Counter>()
        val years = parts.count { it is NumberPart.Year }
        val types = parts.count { it == NumberPart.Type }
        val devices = parts.count { it == NumberPart.Device }
        val unreadable = parts.any { it is NumberPart.Unknown }
        // An unreadable token may be the one that seems to be missing: it is reported alone.
        val missing = setOfNotNull(
            NumberingProblem.TYPE_MISSING.takeIf { types == 0 },
            NumberingProblem.COUNTER_MISSING.takeIf { counters.isEmpty() },
            NumberingProblem.YEAR_MISSING.takeIf { reset == CounterReset.YEARLY && years == 0 },
            NumberingProblem.DEVICE_MISSING.takeIf { multipleDevices && devices == 0 },
        )
        return setOfNotNull(
            NumberingProblem.UNKNOWN_TOKEN.takeIf { unreadable },
            NumberingProblem.TOKEN_REPEATED.takeIf { maxOf(counters.size, years, types, devices) > 1 },
            NumberingProblem.COUNTER_DIGITS.takeIf { counters.any { it.digits > MAX_COUNTER_DIGITS } },
            NumberingProblem.INVALID_CHARACTER.takeIf {
                parts.any { it is NumberPart.Text && !TEXT.matches(it.value) }
            },
        ) + if (unreadable) emptySet() else missing
    }

    private fun lengthProblems(length: Int, identifierRoom: Int?): Set<NumberingProblem> = setOfNotNull(
        NumberingProblem.TOO_LONG.takeIf { length > MAX_NUMBER_LENGTH },
        NumberingProblem.IDENTIFIER_TOO_LONG.takeIf { identifierRoom != null && length > identifierRoom },
    )

    /** Limits and defaults of the numbering. */
    companion object {
        /** Longest `invoice_number` the OBR accepts. */
        const val MAX_NUMBER_LENGTH = 30

        /** Most digits a counter may have (internal rule). */
        const val MAX_COUNTER_DIGITS = 10

        /** Longest device code (internal rule). */
        const val MAX_DEVICE_CODE_LENGTH = 4

        /** Template proposed to a single device, for example `FN-00001/2026`. */
        const val DEFAULT_TEMPLATE = "{TYPE}-{NNNNN}/{YYYY}"

        /** Template proposed when several devices issue documents, for example `A1-FN-00001/2026`. */
        const val DEFAULT_TEMPLATE_WITH_DEVICE = "{DEVICE}-$DEFAULT_TEMPLATE"

        private val TEXT = Regex("[A-Za-z0-9/_.-]+")
        private val DEVICE_CODE = Regex("[A-Z0-9]{1,$MAX_DEVICE_CODE_LENGTH}")

        /**
         * @param multipleDevices whether other devices issue documents for the same NIF.
         * @return the form proposed before anything is typed.
         */
        fun defaultFor(multipleDevices: Boolean): NumberingForm = NumberingForm(
            template = if (multipleDevices) DEFAULT_TEMPLATE_WITH_DEVICE else DEFAULT_TEMPLATE,
            multipleDevices = multipleDevices,
        )

        /**
         * @param settings a stored numbering.
         * @return the form that gives it back.
         */
        fun from(settings: NumberingSettings): NumberingForm =
            NumberingForm(settings.template, settings.reset, settings.multipleDevices, settings.deviceCode)
    }
}
