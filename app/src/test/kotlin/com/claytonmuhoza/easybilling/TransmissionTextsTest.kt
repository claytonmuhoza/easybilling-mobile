package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.configuration.EnvironmentRefusal
import com.claytonmuhoza.easybilling.ebms.SWITCH_REFUSAL_MESSAGES
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every reason fiscal documents prevent a change of environment has its message. */
class TransmissionTextsTest {

    @Test
    fun `every refusal of a change of environment has a message`() {
        assertEquals(EnvironmentRefusal.entries.toSet(), SWITCH_REFUSAL_MESSAGES.keys)
    }
}
