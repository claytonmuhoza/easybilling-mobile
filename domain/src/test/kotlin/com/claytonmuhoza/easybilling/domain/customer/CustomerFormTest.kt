package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Rules of a customer (UC-B05): local or foreign, natural or legal person, TIN, VAT. */
class CustomerFormTest {

    private val company = CustomerForm(
        name = "Brasserie du Lac SA",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.LEGAL_PERSON,
        tin = "4000202020",
        address = "Bujumbura, Rohero",
        phone = "+257 22 22 22 22",
        vatPayer = true,
    )
    private val person = company.copy(name = "NDIKUMANA Jean Marie", personType = TaxpayerType.NATURAL_PERSON)

    private fun valid(form: CustomerForm) = assertIs<CustomerCheck.Valid>(form.check()).draft

    private fun problems(form: CustomerForm, counter: Boolean = false) =
        assertIs<CustomerCheck.Invalid>(form.check(counter)).problems

    @Test
    fun `a complete form gives the customer, trimmed`() {
        val draft = valid(company.copy(name = "  Brasserie du Lac SA ", tin = " 4000202020 ", address = " Rohero "))

        assertEquals(
            CustomerDraft(
                name = "Brasserie du Lac SA",
                origin = CustomerOrigin.LOCAL,
                personType = TaxpayerType.LEGAL_PERSON,
                tin = "4000202020",
                address = "Rohero",
                phone = "+257 22 22 22 22",
                vatPayer = true,
            ),
            draft,
        )
    }

    @Test
    fun `an empty form reports every missing choice at once`() {
        assertEquals(
            setOf(CustomerProblem.NAME_MISSING, CustomerProblem.ORIGIN_MISSING, CustomerProblem.PERSON_TYPE_MISSING),
            problems(CustomerForm()),
        )
    }

    @Test
    fun `a local legal person must have a TIN`() {
        assertEquals(setOf(CustomerProblem.TIN_MISSING), problems(company.copy(tin = " ", vatPayer = false)))
    }

    @Test
    fun `a local natural person may have a TIN or none`() {
        assertEquals("4000202020", valid(person).tin)
        assertNull(valid(person.copy(tin = "", vatPayer = false)).tin)
    }

    @Test
    fun `a foreign customer never has a TIN, whatever was typed`() {
        val foreign = company.copy(origin = CustomerOrigin.FOREIGN, vatPayer = false)

        assertNull(valid(foreign).tin)
        assertNull(valid(foreign.copy(personType = TaxpayerType.NATURAL_PERSON)).tin)
    }

    @Test
    fun `only a customer with a TIN can be registered for the VAT`() {
        assertEquals(setOf(CustomerProblem.VAT_PAYER_WITHOUT_TIN), problems(person.copy(tin = "")))
        assertEquals(
            setOf(CustomerProblem.VAT_PAYER_WITHOUT_TIN),
            problems(company.copy(origin = CustomerOrigin.FOREIGN)),
        )
    }

    @Test
    fun `the name may be left empty with a TIN, since the OBR gives it`() {
        assertEquals("", valid(company.copy(name = " ")).name)
        assertEquals(setOf(CustomerProblem.NAME_MISSING), problems(person.copy(name = "", tin = "", vatPayer = false)))
    }

    @Test
    fun `the sizes of the specification are checked`() {
        assertEquals(100, CustomerField.NAME.maxLength)
        assertEquals(50, CustomerField.TIN.maxLength)
        assertEquals(100, CustomerField.ADDRESS.maxLength)

        valid(company.copy(name = "N".repeat(100), tin = "4".repeat(50), address = "A".repeat(100)))
        assertEquals(
            setOf(CustomerProblem.NAME_TOO_LONG, CustomerProblem.TIN_TOO_LONG, CustomerProblem.ADDRESS_TOO_LONG),
            problems(company.copy(name = "N".repeat(101), tin = "4".repeat(51), address = "A".repeat(101))),
        )
    }

    @Test
    fun `the phone number is optional and limited`() {
        assertEquals("", valid(company.copy(phone = "  ")).phone)
        valid(company.copy(phone = "1".repeat(CustomerForm.MAX_PHONE_LENGTH)))
        assertEquals(
            setOf(CustomerProblem.PHONE_TOO_LONG),
            problems(company.copy(phone = "1".repeat(CustomerForm.MAX_PHONE_LENGTH + 1))),
        )
    }

    @Test
    fun `only the name of the counter customer is checked`() {
        val counter = CustomerForm(name = " Client comptoir ")

        val draft = assertIs<CustomerCheck.Valid>(counter.check(counter = true)).draft
        assertEquals("Client comptoir", draft.name)
        assertNull(draft.tin)
        assertEquals(false, draft.vatPayer)

        assertEquals(setOf(CustomerProblem.NAME_MISSING), problems(CustomerForm(), counter = true))
        assertEquals(
            setOf(CustomerProblem.NAME_TOO_LONG),
            problems(CustomerForm(name = "N".repeat(101)), counter = true),
        )
    }

    @Test
    fun `a stored customer gives back its form`() {
        val details = CustomerDetails(
            name = "Brasserie du Lac SA",
            origin = CustomerOrigin.LOCAL,
            personType = TaxpayerType.LEGAL_PERSON,
            tin = VerifiedTin("4000202020", "BRASSERIE DU LAC", java.time.Instant.parse("2026-10-03T08:00:00Z")),
            address = "Bujumbura, Rohero",
            phone = "+257 22 22 22 22",
            vatPayer = true,
        )

        assertEquals(company, CustomerForm.from(Customer(CustomerId(1), details)))
    }
}
