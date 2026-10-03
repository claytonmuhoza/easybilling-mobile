package com.claytonmuhoza.easybilling.domain.invoicing

/**
 * The types of document of the OBR (`invoice_type`). Each one has its own sequence of numbers
 * (UC-A06).
 *
 * @property code the value sent to the OBR, also written in every number.
 */
enum class DocumentType(val code: String) {
    /** Facture normale: a sale. */
    FN("FN"),

    /** Facture d'avoir: a credit note. */
    FA("FA"),

    /** Remboursement caution: a deposit refund. */
    RC("RC"),

    /** Réduction hors facture: a discount granted after the sale. */
    RHF("RHF"),
}
