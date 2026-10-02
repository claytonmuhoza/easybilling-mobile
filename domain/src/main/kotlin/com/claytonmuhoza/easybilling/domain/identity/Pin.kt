package com.claytonmuhoza.easybilling.domain.identity

/**
 * A PIN that satisfies the PIN rule: [MIN_LENGTH] to [MAX_LENGTH] digits (internal rule).
 *
 * Only [choose] creates one, so an instance is always valid.
 *
 * @property digits the PIN itself; never log it.
 */
class Pin private constructor(val digits: String) {

    /** Never reveals the digits, so a PIN cannot leak through a log or an error message. */
    override fun toString(): String = "Pin(******)"

    /** Limits of the rule. */
    companion object {
        /** Shortest PIN accepted. */
        const val MIN_LENGTH = 6

        /** Longest PIN accepted. */
        const val MAX_LENGTH = 12

        /**
         * Checks a PIN chosen by a user.
         *
         * @param raw the PIN as typed.
         * @param confirmation the PIN typed a second time.
         * @return the valid PIN, or the first rule it breaks.
         */
        fun choose(raw: String, confirmation: String): PinChoice = when {
            raw.any { it !in '0'..'9' } -> PinChoice.Refused(PinProblem.NOT_DIGITS)
            raw.length < MIN_LENGTH -> PinChoice.Refused(PinProblem.TOO_SHORT)
            raw.length > MAX_LENGTH -> PinChoice.Refused(PinProblem.TOO_LONG)
            raw != confirmation -> PinChoice.Refused(PinProblem.MISMATCH)
            else -> PinChoice.Accepted(Pin(raw))
        }
    }
}

/** Outcome of [Pin.choose]. */
sealed interface PinChoice {
    /** The PIN satisfies every rule. */
    data class Accepted(val pin: Pin) : PinChoice

    /** The PIN breaks a rule. */
    data class Refused(val problem: PinProblem) : PinChoice
}

/** A rule broken by a chosen PIN. */
enum class PinProblem {
    /** Contains something other than the digits 0 to 9. */
    NOT_DIGITS,

    /** Shorter than [Pin.MIN_LENGTH]. */
    TOO_SHORT,

    /** Longer than [Pin.MAX_LENGTH]. */
    TOO_LONG,

    /** The confirmation differs from the PIN. */
    MISMATCH,
}
