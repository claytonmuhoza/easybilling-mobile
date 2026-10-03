package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Rules of the number template (UC-A06, `docs/architecture.md` § 7.3). */
class NumberingFormTest {

    private val default = NumberingForm()

    private fun template(text: String, reset: CounterReset = CounterReset.YEARLY) =
        default.copy(template = text, reset = reset)

    private fun settings(form: NumberingForm, room: Int? = null) =
        assertIs<NumberingCheck.Valid>(form.check(room)).settings

    private fun problems(form: NumberingForm, room: Int? = null) =
        assertIs<NumberingCheck.Invalid>(form.check(room)).problems

    @Test
    fun `the default template is TYPE-counter slash year, reset every year`() {
        assertEquals("{TYPE}-{NNNNN}/{YYYY}", default.template)
        assertEquals(CounterReset.YEARLY, default.reset)
        assertEquals("FN-00001/2026", settings(default).render(DocumentType.FN, year = 2026, counter = 1))
        assertEquals("RHF-00042/2026", settings(default).render(DocumentType.RHF, year = 2026, counter = 42))
    }

    @Test
    fun `with several devices the default template starts with the device code`() {
        val form = NumberingForm.defaultFor(multipleDevices = true).copy(deviceCode = "A1")

        assertEquals("{DEVICE}-{TYPE}-{NNNNN}/{YYYY}", form.template)
        assertEquals("A1-FA-00007/2027", settings(form).render(DocumentType.FA, year = 2027, counter = 7))
    }

    @Test
    fun `a year may have two digits`() {
        assertEquals("FN26-001", settings(template("{TYPE}{YY}-{NNN}")).render(DocumentType.FN, 2026, 1))
    }

    @Test
    fun `every template carries the type of document`() {
        assertEquals(setOf(NumberingProblem.TYPE_MISSING), problems(template("{NNNNN}/{YYYY}")))
    }

    @Test
    fun `every template has exactly one counter of 1 to 10 digits`() {
        assertEquals(setOf(NumberingProblem.COUNTER_MISSING), problems(template("{TYPE}-{YYYY}")))
        assertEquals(setOf(NumberingProblem.TOKEN_REPEATED), problems(template("{TYPE}-{NN}-{NNN}/{YYYY}")))
        assertEquals(setOf(NumberingProblem.COUNTER_DIGITS), problems(template("{TYPE}-{NNNNNNNNNNN}/{YYYY}")))
        assertEquals(
            "FN-0000000001",
            settings(template("{TYPE}-{NNNNNNNNNN}", CounterReset.NEVER)).render(DocumentType.FN, 2026, 1),
        )
    }

    @Test
    fun `a token appears once`() {
        assertEquals(setOf(NumberingProblem.TOKEN_REPEATED), problems(template("{TYPE}{TYPE}-{NNNNN}/{YYYY}")))
        assertEquals(setOf(NumberingProblem.TOKEN_REPEATED), problems(template("{TYPE}-{NNNNN}/{YYYY}{YY}")))
    }

    @Test
    fun `a yearly reset needs the year in the number`() {
        assertEquals(setOf(NumberingProblem.YEAR_MISSING), problems(template("{TYPE}-{NNNNN}")))
        assertIs<NumberingCheck.Valid>(template("{TYPE}-{NNNNN}", CounterReset.NEVER).check(null))
    }

    @Test
    fun `several devices need the device code in the number`() {
        val form = default.copy(multipleDevices = true, deviceCode = "A")

        assertEquals(setOf(NumberingProblem.DEVICE_MISSING), problems(form))
    }

    @Test
    fun `a device code has 1 to 4 capital letters or digits, typed in any case`() {
        val form = NumberingForm.defaultFor(multipleDevices = true)

        for (code in listOf("", "A B", "ABCDE", "A-1", "É")) {
            assertEquals(setOf(NumberingProblem.DEVICE_CODE_INVALID), problems(form.copy(deviceCode = code)), code)
        }
        assertEquals("C12-FN-00001/2026", settings(form.copy(deviceCode = " c12 ")).render(DocumentType.FN, 2026, 1))
    }

    @Test
    fun `a single device may still put a code in its numbers`() {
        val form = default.copy(template = "{DEVICE}-{TYPE}-{NNNNN}/{YYYY}", deviceCode = "Z")

        assertEquals("Z-FN-00001/2026", settings(form).render(DocumentType.FN, 2026, 1))
        assertEquals(setOf(NumberingProblem.DEVICE_CODE_INVALID), problems(form.copy(deviceCode = "")))
    }

    @Test
    fun `an unknown or unclosed token is refused`() {
        assertEquals(setOf(NumberingProblem.UNKNOWN_TOKEN), problems(template("{TYPE}-{FOO}-{NNNNN}/{YYYY}")))
        assertEquals(setOf(NumberingProblem.UNKNOWN_TOKEN), problems(template("{TYPE}-{NNNNN}/{YYYY")))
    }

    @Test
    fun `the text of a template is made of letters, digits and - slash _ dot`() {
        assertEquals(setOf(NumberingProblem.INVALID_CHARACTER), problems(template("{TYPE} {NNNNN}/{YYYY}")))
        assertEquals(setOf(NumberingProblem.INVALID_CHARACTER), problems(template("É{TYPE}{NNNNN}/{YYYY}")))
        assertEquals(
            "FAC.FN_00001-2026",
            settings(template("FAC.{TYPE}_{NNNNN}-{YYYY}")).render(DocumentType.FN, 2026, 1),
        )
    }

    @Test
    fun `a number never exceeds 30 characters, counted with the longest type`() {
        // RHF (3) + 17 text + 10 digits = 30: accepted.
        val longest = template("{TYPE}ABCDEFGHIJKLMNOPQ{NNNNNNNNNN}", CounterReset.NEVER)
        assertIs<NumberingCheck.Valid>(longest.check(null))
        // One character more: refused.
        assertEquals(
            setOf(NumberingProblem.TOO_LONG),
            problems(template("{TYPE}ABCDEFGHIJKLMNOPQR{NNNNNNNNNN}", CounterReset.NEVER)),
        )
        // RHF-00001/2026.
        assertEquals(14, settings(default).maxLength)
    }

    @Test
    fun `a number must also fit in the identifier of the invoice`() {
        assertEquals(setOf(NumberingProblem.IDENTIFIER_TOO_LONG), problems(default, room = 13))
        assertIs<NumberingCheck.Valid>(default.check(14))
    }

    @Test
    fun `every problem is reported at once`() {
        val form = NumberingForm("{NN} x", CounterReset.YEARLY, multipleDevices = true, deviceCode = "")

        assertEquals(
            setOf(
                NumberingProblem.TYPE_MISSING,
                NumberingProblem.YEAR_MISSING,
                NumberingProblem.DEVICE_MISSING,
                NumberingProblem.DEVICE_CODE_INVALID,
                NumberingProblem.INVALID_CHARACTER,
            ),
            problems(form),
        )
    }

    @Test
    fun `settings turned back into a form give the same settings`() {
        val original = settings(NumberingForm.defaultFor(multipleDevices = true).copy(deviceCode = "B2"))

        assertEquals(original, settings(NumberingForm.from(original)))
    }
}
