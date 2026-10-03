package com.claytonmuhoza.easybilling

import com.claytonmuhoza.easybilling.domain.configuration.CounterReset
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.numbering.NumberingFields
import com.claytonmuhoza.easybilling.numbering.YearFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The fields of the numbering screen compose the template of the domain, and read it back. */
class NumberingFieldsTest {

    private fun saved(fields: NumberingFields) =
        assertIs<NumberingCheck.Valid>(fields.toForm().check(identifierRoom = null)).settings

    @Test
    fun `the fields proposed first compose the default template`() {
        assertEquals(NumberingForm.DEFAULT_TEMPLATE, NumberingFields().template)
        assertEquals(NumberingForm(), NumberingFields().toForm())
    }

    @Test
    fun `with several devices the device code comes first`() {
        val fields = NumberingFields(multipleDevices = true, deviceCode = "A1")

        assertEquals(NumberingForm.DEFAULT_TEMPLATE_WITH_DEVICE, fields.template)
        assertEquals("A1", saved(fields).deviceCode)
    }

    @Test
    fun `every field takes its place in the template`() {
        val fields = NumberingFields(
            prefix = " FAC. ",
            separator = "_",
            counterDigits = 8,
            year = YearFormat.SHORT,
            yearSeparator = "",
            reset = CounterReset.NEVER,
        )

        assertEquals("FAC.{TYPE}_{NNNNNNNN}{YY}", fields.template)
    }

    @Test
    fun `without a year the template ends with the counter`() {
        val fields = NumberingFields(year = YearFormat.NONE, separator = "", reset = CounterReset.NEVER)

        assertEquals("{TYPE}{NNNNN}", fields.template)
    }

    @Test
    fun `saved fields are read back as they were composed`() {
        val all = listOf(
            NumberingFields(),
            NumberingFields(multipleDevices = true, deviceCode = "A1"),
            NumberingFields(multipleDevices = true, deviceCode = "Z", separator = "/", yearSeparator = "-"),
            NumberingFields(prefix = "FAC.", separator = "_", counterDigits = 8, year = YearFormat.SHORT)
                .copy(yearSeparator = ""),
            NumberingFields(prefix = "B-", separator = "", year = YearFormat.NONE, reset = CounterReset.NEVER),
        )

        for (fields in all) {
            assertEquals(fields, NumberingFields.from(saved(fields)), fields.template)
        }
    }
}
