package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import java.time.Instant

/** When the counters of the numbers start again from 1. */
enum class CounterReset {
    /** Never: a counter goes on across years. */
    NEVER,

    /** On the first of January, in Bujumbura. */
    YEARLY,
    ;

    /**
     * @param year the year a document is issued in.
     * @return the period whose counter the document uses.
     */
    fun periodOf(year: Int): Int = when (this) {
        NEVER -> NO_PERIOD
        YEARLY -> year
    }

    private companion object {
        const val NO_PERIOD = 0
    }
}

/** A piece of a number template. */
internal sealed interface NumberPart {
    /** Text copied as it is. */
    data class Text(val value: String) : NumberPart

    /** `{TYPE}`: the code of the type of document. */
    data object Type : NumberPart

    /** `{DEVICE}`: the code of the device. */
    data object Device : NumberPart

    /** `{YYYY}` or `{YY}`: the year, on [digits] digits. */
    data class Year(val digits: Int) : NumberPart

    /** `{NNN}`: the counter, on [digits] digits. */
    data class Counter(val digits: Int) : NumberPart

    /** Something between braces that is not a token, or a brace alone. */
    data class Unknown(val raw: String) : NumberPart
}

/** Reads a number template such as `{TYPE}-{NNNNN}/{YYYY}`. */
internal object NumberTemplate {
    private val TOKEN = Regex("""\{([^{}]*)\}""")
    private const val FULL_YEAR_DIGITS = 4
    private const val SHORT_YEAR_DIGITS = 2

    /**
     * @param template the template as typed.
     * @return its pieces, in order.
     */
    fun parse(template: String): List<NumberPart> {
        val parts = mutableListOf<NumberPart>()
        var index = 0
        for (match in TOKEN.findAll(template)) {
            text(template.substring(index, match.range.first))?.let(parts::add)
            parts += token(match.groupValues[1])
            index = match.range.last + 1
        }
        text(template.substring(index))?.let(parts::add)
        return parts
    }

    private fun text(value: String): NumberPart? = when {
        value.isEmpty() -> null
        value.any { it == '{' || it == '}' } -> NumberPart.Unknown(value)
        else -> NumberPart.Text(value)
    }

    private fun token(name: String): NumberPart = when {
        name == "TYPE" -> NumberPart.Type
        name == "DEVICE" -> NumberPart.Device
        name == "YYYY" -> NumberPart.Year(FULL_YEAR_DIGITS)
        name == "YY" -> NumberPart.Year(SHORT_YEAR_DIGITS)
        name.isNotEmpty() && name.all { it == 'N' } -> NumberPart.Counter(name.length)
        else -> NumberPart.Unknown("{$name}")
    }
}

/**
 * How the numbers of the documents are built (UC-A06, `docs/architecture.md` § 7.3).
 *
 * Instances come from [NumberingForm.check] or from the storage, so every rule of
 * [NumberingProblem] holds.
 *
 * @property template the template, for example `{TYPE}-{NNNNN}/{YYYY}`.
 * @property reset when the counters start again.
 * @property multipleDevices whether other devices issue documents for the same NIF.
 * @property deviceCode the code of this device, or an empty text if it has none.
 * @property lockedAt when the first number was given; from then on nothing changes any more.
 */
data class NumberingSettings(
    val template: String,
    val reset: CounterReset,
    val multipleDevices: Boolean,
    val deviceCode: String,
    val lockedAt: Instant? = null,
) {
    private val parts: List<NumberPart> get() = NumberTemplate.parse(template)

    /** Whether a number was already given. */
    val locked: Boolean get() = lockedAt != null

    /** Length of the longest number the template can give. */
    val maxLength: Int
        get() = parts.sumOf { part ->
            when (part) {
                is NumberPart.Text -> part.value.length
                NumberPart.Type -> DocumentType.entries.maxOf { it.code.length }
                NumberPart.Device -> deviceCode.length
                is NumberPart.Year -> part.digits
                is NumberPart.Counter -> part.digits
                is NumberPart.Unknown -> part.raw.length
            }
        }

    /** The highest counter that fits in the digits of the template. */
    val maxCounter: Long
        get() = generateSequence(1L) { it * DECIMAL }.elementAt(counterDigits) - 1

    private val counterDigits: Int get() = parts.filterIsInstance<NumberPart.Counter>().single().digits

    /**
     * @param type the type of the document.
     * @param year the year the document is issued in, in Bujumbura.
     * @param counter the rank of the document in its sequence, from 1 to [maxCounter].
     * @return the number of the document.
     * @throws IllegalArgumentException if [counter] does not fit in the template.
     */
    fun render(type: DocumentType, year: Int, counter: Long): String {
        require(counter in 1..maxCounter) { "Counter $counter does not fit in $template" }
        return parts.joinToString("") { part ->
            when (part) {
                is NumberPart.Text -> part.value
                NumberPart.Type -> type.code
                NumberPart.Device -> deviceCode
                is NumberPart.Year -> year.toString().padStart(part.digits, '0').takeLast(part.digits)
                is NumberPart.Counter -> counter.toString().padStart(part.digits, '0')
                is NumberPart.Unknown -> part.raw
            }
        }
    }

    private companion object {
        const val DECIMAL = 10L
    }
}

/** Storage of the numbering and of its counters. */
interface NumberingRepository {
    /** @return the numbering, or null if none was ever saved. */
    suspend fun settings(): NumberingSettings?

    /** @param settings the numbering that replaces the stored one. */
    suspend fun save(settings: NumberingSettings)

    /**
     * @param type a type of document.
     * @param period the period of the counter (see [CounterReset.periodOf]).
     * @return the last counter given, or 0 if none was.
     */
    suspend fun lastCounter(type: DocumentType, period: Int): Long

    /**
     * @param type a type of document.
     * @param period the period of the counter.
     * @param counter the last counter given.
     */
    suspend fun setLastCounter(type: DocumentType, period: Int, counter: Long)
}
