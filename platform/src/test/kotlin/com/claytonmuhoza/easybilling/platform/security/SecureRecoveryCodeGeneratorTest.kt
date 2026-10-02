package com.claytonmuhoza.easybilling.platform.security

import com.claytonmuhoza.easybilling.domain.identity.RecoveryCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureRecoveryCodeGeneratorTest {

    private val generator = SecureRecoveryCodeGenerator()

    @Test
    fun `a code has twelve characters`() {
        assertEquals(12, generator.generate().value.length)
    }

    @Test
    fun `a code avoids the characters that are easily confused`() {
        repeat(200) {
            val code = generator.generate().value

            assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }, "Unexpected character in $code")
        }
    }

    @Test
    fun `codes are not repeated`() {
        val codes = List(200) { generator.generate().value }

        assertEquals(200, codes.toSet().size)
    }

    @Test
    fun `a code is shown in three groups and typed back to the same value`() {
        val code = generator.generate()

        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code.formatted))
        assertEquals(code.value, RecoveryCode.normalize(code.formatted.lowercase()))
    }
}
