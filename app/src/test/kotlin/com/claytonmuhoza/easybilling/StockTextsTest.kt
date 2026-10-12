package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.stock.ImportProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementProblem
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.stock.IMPORT_PROBLEM_MESSAGES
import com.claytonmuhoza.easybilling.stock.MOVEMENT_PROBLEM_MESSAGES
import com.claytonmuhoza.easybilling.stock.TYPE_LABELS
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every type and every broken rule of a stock movement has its words: none can be added without them. */
class StockTextsTest {

    @Test
    fun `every type of movement has a name`() {
        assertEquals(MovementType.entries.toSet(), TYPE_LABELS.keys)
        assertEquals(MovementType.entries.size, TYPE_LABELS.values.toSet().size)
    }

    @Test
    fun `every problem of a movement has a message`() {
        assertEquals(MovementProblem.entries.toSet(), MOVEMENT_PROBLEM_MESSAGES.keys)
    }

    @Test
    fun `every problem of an entry of imported goods has a message`() {
        assertEquals(ImportProblem.entries.toSet(), IMPORT_PROBLEM_MESSAGES.keys)
    }
}
