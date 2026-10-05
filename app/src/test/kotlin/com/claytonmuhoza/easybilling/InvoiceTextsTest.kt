package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.invoicing.InvoicePeriod
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatusKind
import com.claytonmuhoza.easybilling.domain.transmission.InvoiceDifference
import com.claytonmuhoza.easybilling.invoices.DIFFERENCE_MESSAGES
import com.claytonmuhoza.easybilling.invoices.PERIOD_LABELS
import com.claytonmuhoza.easybilling.invoices.STATUS_FILTER_LABELS
import com.claytonmuhoza.easybilling.invoices.STATUS_LABELS
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every status, period and difference the invoice screens show has its words. */
class InvoiceTextsTest {

    @Test
    fun `every status has a name on an invoice and as a filter`() {
        assertEquals(InvoiceStatusKind.entries.toSet(), STATUS_LABELS.keys)
        assertEquals(InvoiceStatusKind.entries.toSet(), STATUS_FILTER_LABELS.keys)
    }

    @Test
    fun `every period has a name`() {
        assertEquals(InvoicePeriod.entries.toSet(), PERIOD_LABELS.keys)
    }

    @Test
    fun `every difference with the OBR has a sentence`() {
        assertEquals(InvoiceDifference.entries.toSet(), DIFFERENCE_MESSAGES.keys)
    }
}
