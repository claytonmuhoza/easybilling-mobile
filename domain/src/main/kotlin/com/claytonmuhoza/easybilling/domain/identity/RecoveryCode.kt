package com.claytonmuhoza.easybilling.domain.identity

/**
 * Code shown once to a user, to be kept on paper, that lets them choose a new PIN when the PIN is
 * forgotten (internal rule).
 *
 * @property value the code in canonical form: upper case letters and digits, no separator.
 */
@JvmInline
value class RecoveryCode(val value: String) {

    /** The code in groups of [GROUP_SIZE] characters separated by dashes, easier to copy by hand. */
    val formatted: String
        get() = value.chunked(GROUP_SIZE).joinToString("-")

    /** Never reveals the code, so it cannot leak through a log or an error message. */
    override fun toString(): String = "RecoveryCode(****)"

    /** Format of a code. */
    companion object {
        /** Number of characters between two dashes of the [formatted] code. */
        const val GROUP_SIZE = 4

        /**
         * Brings a code typed by a user to canonical form: spaces and dashes removed, upper case.
         *
         * @param raw the code as typed.
         * @return the canonical form to compare with the stored code.
         */
        fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()
    }
}

/** Creates unpredictable recovery codes. */
fun interface RecoveryCodeGenerator {
    /** @return a new random code. */
    fun generate(): RecoveryCode
}
