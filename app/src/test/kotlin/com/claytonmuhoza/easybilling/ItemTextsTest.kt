package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.catalog.ItemProblem
import com.claytonmuhoza.easybilling.items.PROBLEM_MESSAGES
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every broken rule of an item has its message: a new rule cannot be added without one. */
class ItemTextsTest {

    @Test
    fun `every problem of an item has a message`() {
        assertEquals(ItemProblem.entries.toSet(), PROBLEM_MESSAGES.keys)
    }
}
