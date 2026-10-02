package com.claytonmuhoza.easybilling.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PinTest {

    @Test
    fun `six digits confirmed identically are accepted`() {
        val choice = assertIs<PinChoice.Accepted>(Pin.choose("123456", "123456"))

        assertEquals("123456", choice.pin.digits)
    }

    @Test
    fun `twelve digits are accepted`() {
        assertIs<PinChoice.Accepted>(Pin.choose("123456789012", "123456789012"))
    }

    @Test
    fun `five digits are too short`() {
        assertEquals(PinChoice.Refused(PinProblem.TOO_SHORT), Pin.choose("12345", "12345"))
    }

    @Test
    fun `thirteen digits are too long`() {
        assertEquals(PinChoice.Refused(PinProblem.TOO_LONG), Pin.choose("1234567890123", "1234567890123"))
    }

    @Test
    fun `letters are refused`() {
        assertEquals(PinChoice.Refused(PinProblem.NOT_DIGITS), Pin.choose("12345a", "12345a"))
    }

    @Test
    fun `non ASCII digits are refused`() {
        assertEquals(PinChoice.Refused(PinProblem.NOT_DIGITS), Pin.choose("١٢٣٤٥٦", "١٢٣٤٥٦"))
    }

    @Test
    fun `a different confirmation is a mismatch`() {
        assertEquals(PinChoice.Refused(PinProblem.MISMATCH), Pin.choose("123456", "123457"))
    }

    @Test
    fun `the digits never appear in the text form`() {
        val choice = assertIs<PinChoice.Accepted>(Pin.choose("123456", "123456"))

        assertFalse("123456" in choice.pin.toString())
        assertFalse("123456" in choice.toString())
    }
}
