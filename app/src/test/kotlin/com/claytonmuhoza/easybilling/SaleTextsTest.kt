package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.SaleLineProblem
import com.claytonmuhoza.easybilling.domain.invoicing.SaleProblem
import com.claytonmuhoza.easybilling.sale.IssueRefusal
import com.claytonmuhoza.easybilling.sale.LINE_PROBLEM_MESSAGES
import com.claytonmuhoza.easybilling.sale.PAYMENT_LABELS
import com.claytonmuhoza.easybilling.sale.REFUSAL_MESSAGES
import com.claytonmuhoza.easybilling.sale.SALE_PROBLEM_MESSAGES
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every way to pay, every problem and every refusal of a sale has its text. */
class SaleTextsTest {

    @Test
    fun `every way to pay has a name`() {
        assertEquals(PaymentType.entries.toSet(), PAYMENT_LABELS.keys)
    }

    @Test
    fun `every reason a sale cannot be issued has a message`() {
        assertEquals(SaleProblem.entries.toSet(), SALE_PROBLEM_MESSAGES.keys)
        assertEquals(IssueRefusal.entries.toSet(), REFUSAL_MESSAGES.keys)
    }

    @Test
    fun `every problem of a line has a message`() {
        assertEquals(SaleLineProblem.entries.toSet(), LINE_PROBLEM_MESSAGES.keys)
    }
}
