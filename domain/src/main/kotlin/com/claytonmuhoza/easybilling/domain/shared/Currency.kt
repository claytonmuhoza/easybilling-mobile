package com.claytonmuhoza.easybilling.domain.shared

/** Currencies accepted by the OBR (`invoice_currency`, `item_cost_price_currency`). The name is the code. */
enum class Currency {
    /** Burundian franc, the default of the OBR. */
    BIF,

    /** United States dollar. */
    USD,

    /** Euro. */
    EUR,
}
