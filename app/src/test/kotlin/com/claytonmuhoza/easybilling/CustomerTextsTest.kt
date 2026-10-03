package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.customers.CUSTOMER_PROBLEM_MESSAGES
import com.claytonmuhoza.easybilling.customers.TIN_FAILURE_MESSAGES
import com.claytonmuhoza.easybilling.domain.customer.CustomerProblem
import com.claytonmuhoza.easybilling.domain.customer.TinCheckFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every broken rule and every failed check has its message: a new one cannot be added without it. */
class CustomerTextsTest {

    @Test
    fun `every problem of a customer has a message`() {
        assertEquals(CustomerProblem.entries.toSet(), CUSTOMER_PROBLEM_MESSAGES.keys)
    }

    @Test
    fun `every reason a TIN could not be checked has a message`() {
        assertEquals(TinCheckFailure.entries.toSet(), TIN_FAILURE_MESSAGES.keys)
    }
}
