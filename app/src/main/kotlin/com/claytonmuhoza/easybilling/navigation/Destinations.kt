package com.claytonmuhoza.easybilling.navigation

import kotlinx.serialization.Serializable

/** First launch assistant: creation of the administrator account. */
@Serializable
data object SetupDestination

/** Login by PIN. */
@Serializable
data object LoginDestination

/**
 * Choice of a new PIN with the recovery code.
 *
 * @property userId identifier of the user who forgot the PIN.
 */
@Serializable
data class ResetPinDestination(val userId: Long)

/** The accounts of the users. */
@Serializable
data object UsersDestination

/**
 * Creation or change of an account.
 *
 * @property id identifier of the account, or [NEW_USER] for a new one.
 */
@Serializable
data class UserEditorDestination(val id: Long = NEW_USER) {
    /** Identifiers of the destination. */
    companion object {
        /** The identifier meaning "a new account". */
        const val NEW_USER = 0L
    }
}

/** Home screen, the "Register" tab: the start of the application once logged in. */
@Serializable
data object HomeDestination

/** The settings: what is configured before the first invoice, and the logout. */
@Serializable
data object SettingsDestination

/** The logo, the footer and the QR code of the documents. */
@Serializable
data object DocumentSettingsDestination

/** The printer of the tickets and how it prints. */
@Serializable
data object PrinterSettingsDestination

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

/** Numbering of the documents. */
@Serializable
data object NumberingDestination

/** The list of the items of the catalogue. */
@Serializable
data object ItemsDestination

/**
 * Creation or change of an item.
 *
 * @property id identifier of the item, or [NEW_ITEM] for a new one.
 */
@Serializable
data class ItemEditorDestination(val id: Long = NEW_ITEM) {
    /** Identifiers of the destination. */
    companion object {
        /** The identifier meaning "a new item". */
        const val NEW_ITEM = 0L
    }
}

/** The list of the customers. */
@Serializable
data object CustomersDestination

/**
 * Creation or change of a customer.
 *
 * @property id identifier of the customer, or [NEW_CUSTOMER] for a new one.
 */
@Serializable
data class CustomerEditorDestination(val id: Long = NEW_CUSTOMER) {
    /** Identifiers of the destination. */
    companion object {
        /** The identifier meaning "a new customer". */
        const val NEW_CUSTOMER = 0L
    }
}

/** The cash register: the sale being typed. */
@Serializable
data object SaleDestination

/** Choice of the customer of the sale being typed. */
@Serializable
data object SaleCustomerDestination

/** The sales put on hold. */
@Serializable
data object HeldSalesDestination

/**
 * Confirmation of the issue of an invoice.
 *
 * @property id identifier of the invoice.
 */
@Serializable
data class InvoiceIssuedDestination(val id: Long)

/** The list of the issued invoices, the "Invoices" tab. */
@Serializable
data object InvoicesDestination

/**
 * The detail of an issued invoice.
 *
 * @property id identifier of the invoice.
 */
@Serializable
data class InvoiceDestination(val id: Long)

/** The transmission to the OBR: the network, the send queue and the refused invoices. */
@Serializable
data object TransmissionDestination

/** Quick switch to another user, without closing the register. */
@Serializable
data object SwitchUserDestination

/** The cap of the discount of the cashier. */
@Serializable
data object DiscountCapDestination
