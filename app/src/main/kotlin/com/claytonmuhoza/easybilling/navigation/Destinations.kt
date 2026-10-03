package com.claytonmuhoza.easybilling.navigation

import kotlinx.serialization.Serializable

/** First launch assistant: creation of the administrator account. */
@Serializable
data object SetupDestination

/** Login by PIN. */
@Serializable
data object LoginDestination

/** Choice of a new PIN with the recovery code. */
@Serializable
data object ResetPinDestination

/** Home screen, the start of the application once logged in. */
@Serializable
data object HomeDestination

/** Entry and change of the tax profile. */
@Serializable
data object TaxProfileDestination

/** Saved versions of the tax profile. */
@Serializable
data object TaxProfileHistoryDestination

/**
 * One saved version of the tax profile, read only.
 *
 * @property number number of the version.
 */
@Serializable
data class TaxProfileVersionDestination(val number: Int)

/** Settings and test of the connection to the EBMS. */
@Serializable
data object EbmsConnectionDestination

/** Log of the exchanges with the OBR. */
@Serializable
data object ExchangeLogDestination

/**
 * One exchange with the OBR.
 *
 * @property id identifier of the exchange in the log.
 */
@Serializable
data class ExchangeDestination(val id: Long)

/** The list of the taxes. */
@Serializable
data object TaxesDestination

/**
 * Creation of a tax, or the rates of an existing one.
 *
 * @property id identifier of the tax, or [NEW_TAX] for a new one.
 */
@Serializable
data class TaxEditorDestination(val id: Long = NEW_TAX) {
    /** Identifiers of the destination. */
    companion object {
        /** The identifier meaning "a new tax". */
        const val NEW_TAX = 0L
    }
}

/** Preview of the calculation of a line. */
@Serializable
data object LinePreviewDestination
