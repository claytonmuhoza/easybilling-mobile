package com.claytonmuhoza.easybilling.domain.customer

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsServerAddress
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.FakeAccessControl
import com.claytonmuhoza.easybilling.domain.testing.FakeTinLookup
import com.claytonmuhoza.easybilling.domain.testing.FixedTokenProvider
import com.claytonmuhoza.easybilling.domain.testing.InMemoryCustomerRepository
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Creating, changing, archiving and finding the customers (UC-B05, B06, B07). */
class CustomerUseCasesTest {

    private val customers = InMemoryCustomerRepository()
    private val audit = RecordingAuditLog()
    private val time = MutableTimeProvider()
    private val lookup = FakeTinLookup().apply {
        known["4000202020"] = "BRASSERIE DU LAC"
        known["4000303030"] = "NDIKUMANA Jean Marie"
    }
    private val target =
        LoginTarget(EbmsEnvironment.TEST, EbmsServerAddress.DEFAULT_TEST, EbmsCredentials("ws1", "pass"))
    private val verify = VerifyTin({ target }, FixedTokenProvider(), lookup)
    private val access = FakeAccessControl()
    private val create = CreateCustomer(access, customers, verify, audit, DirectTransactionRunner, time)
    private val update = UpdateCustomer(access, customers, verify, audit, DirectTransactionRunner, time)
    private val archive = ArchiveCustomer(access, customers, audit, DirectTransactionRunner)
    private val alice = UserId(1)

    private val company = CustomerForm(
        name = "Brasserie du Lac SA",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.LEGAL_PERSON,
        tin = "4000202020",
        address = "Bujumbura, Rohero",
        phone = "+257 22 22 22 22",
        vatPayer = true,
    )
    private val walkIn = CustomerForm(
        name = "Aline Irakoze",
        origin = CustomerOrigin.LOCAL,
        personType = TaxpayerType.NATURAL_PERSON,
    )

    private suspend fun created(form: CustomerForm) = assertIs<CustomerSaveResult.Saved>(create(form, alice)).customer

    @Test
    fun `a customer without a TIN is created without asking the OBR`() = runTest {
        val customer = created(walkIn)

        assertEquals("Aline Irakoze", customer.details.name)
        assertNull(customer.details.tin)
        assertEquals(customer, customers.find(customer.id))
        assertEquals(emptyList(), lookup.calls)
        assertEquals(AuditEvent.CustomerCreated(alice, customer.id), audit.events.single())
        assertEquals("customer=${customer.id.value}", audit.events.single().details)
    }

    @Test
    fun `a TIN the OBR knows is stored with the name of its owner and the time of the check`() = runTest {
        val customer = created(company)

        assertEquals(VerifiedTin("4000202020", "BRASSERIE DU LAC", time.now()), customer.details.tin)
        assertEquals("Brasserie du Lac SA", customer.details.name)
        assertTrue(customer.details.vatPayer)
    }

    @Test
    fun `a name left empty is the one the OBR gives`() = runTest {
        assertEquals("BRASSERIE DU LAC", created(company.copy(name = "")).details.name)
    }

    @Test
    fun `a TIN the OBR does not know is refused and nothing is stored`() = runTest {
        assertEquals(CustomerSaveResult.TinUnknown, create(company.copy(tin = "999"), alice))

        assertEquals(0, customers.count(archived = false))
        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `a TIN that cannot be checked is refused and nothing is stored`() = runTest {
        lookup.forced += TinReply.Unreachable

        assertEquals(CustomerSaveResult.NotVerified(TinCheckFailure.UNREACHABLE, ""), create(company, alice))
        assertEquals(0, customers.count(archived = false))
    }

    @Test
    fun `an invalid form is refused before the OBR is asked`() = runTest {
        val result = create(company.copy(tin = ""), alice)

        assertEquals(
            CustomerSaveResult.Invalid(setOf(CustomerProblem.TIN_MISSING, CustomerProblem.VAT_PAYER_WITHOUT_TIN)),
            result,
        )
        assertEquals(emptyList(), lookup.calls)
    }

    @Test
    fun `a TIN belongs to one customer only`() = runTest {
        created(company)
        lookup.calls.clear()

        val result = create(company.copy(name = "Autre nom"), alice)

        assertEquals(CustomerSaveResult.Invalid(setOf(CustomerProblem.TIN_TAKEN)), result)
        assertEquals(emptyList(), lookup.calls)
        assertEquals(1, customers.count(archived = false))
    }

    @Test
    fun `creating a customer with the TIN of an archived one brings it back`() = runTest {
        val first = created(company)
        archive(first.id, archived = true, actor = alice)
        lookup.calls.clear()
        audit.events.clear()

        val again = company.copy(name = "Brasserie du Lac", phone = "")
        val result = assertIs<CustomerSaveResult.Saved>(create(again, alice))

        assertTrue(result.restored)
        assertEquals(first.id, result.customer.id)
        assertFalse(result.customer.archived)
        assertEquals("Brasserie du Lac", result.customer.details.name)
        assertEquals(first.details.tin, result.customer.details.tin)
        assertEquals(emptyList(), lookup.calls)
        assertEquals(
            listOf(AuditEvent.CustomerUpdated(alice, first.id), AuditEvent.CustomerArchived(alice, first.id, false)),
            audit.events,
        )
    }

    @Test
    fun `changing other fields does not check the TIN again`() = runTest {
        val customer = created(company)
        lookup.calls.clear()
        time.advance(Duration.ofDays(1))

        val result = update(customer.id, company.copy(address = "Gitega"), alice)

        val saved = assertIs<CustomerSaveResult.Saved>(result).customer
        assertEquals("Gitega", saved.details.address)
        assertEquals(customer.details.tin, saved.details.tin)
        assertEquals(emptyList(), lookup.calls)
        assertEquals(AuditEvent.CustomerUpdated(alice, customer.id), audit.events.last())
    }

    @Test
    fun `changing the TIN checks the new one`() = runTest {
        val customer = created(company)
        time.advance(Duration.ofDays(1))

        val saved = assertIs<CustomerSaveResult.Saved>(update(customer.id, company.copy(tin = "4000303030"), alice))

        assertEquals(VerifiedTin("4000303030", "NDIKUMANA Jean Marie", time.now()), saved.customer.details.tin)
        assertEquals(CustomerSaveResult.TinUnknown, update(customer.id, company.copy(tin = "999"), alice))
        assertEquals("4000303030", customers.find(customer.id)?.details?.tin?.number)
    }

    @Test
    fun `a customer cannot take the TIN of another one, archived or not`() = runTest {
        val other = created(company)
        val customer = created(walkIn)
        archive(other.id, archived = true, actor = alice)

        val result = update(customer.id, walkIn.copy(tin = "4000202020"), alice)

        assertEquals(CustomerSaveResult.Invalid(setOf(CustomerProblem.TIN_TAKEN)), result)
    }

    @Test
    fun `a customer who becomes foreign loses its TIN`() = runTest {
        val customer = created(company)

        val foreign = company.copy(origin = CustomerOrigin.FOREIGN, vatPayer = false)
        val saved = assertIs<CustomerSaveResult.Saved>(update(customer.id, foreign, alice))

        assertNull(saved.customer.details.tin)
        assertNull(customers.findByTin("4000202020"))
    }

    @Test
    fun `a customer that does not exist is not changed nor archived`() = runTest {
        assertEquals(CustomerSaveResult.NotFound, update(CustomerId(9), walkIn, alice))
        assertEquals(ArchiveCustomer.Result.NotFound, archive(CustomerId(9), archived = true, actor = alice))
    }

    @Test
    fun `the counter customer exists from the start, without a TIN`() = runTest {
        val counter = customers.counter()

        assertTrue(counter.counter)
        assertEquals(Customer.DEFAULT_COUNTER_NAME, counter.details.name)
        assertNull(counter.details.tin)
        assertFalse(counter.details.vatPayer)
        assertEquals(counter, customers.counter())
        assertEquals(0, customers.count(archived = false))
        assertEquals(emptyList(), customers.search("", archived = false, limit = 10))
    }

    @Test
    fun `only the name of the counter customer changes`() = runTest {
        val counter = customers.counter()

        val renamed = company.copy(name = "Client de passage")
        val saved = assertIs<CustomerSaveResult.Saved>(update(counter.id, renamed, alice))

        assertEquals(Customer.counterDetails().copy(name = "Client de passage"), saved.customer.details)
        assertEquals(emptyList(), lookup.calls)
        assertEquals(
            CustomerSaveResult.Invalid(setOf(CustomerProblem.NAME_MISSING)),
            update(counter.id, CustomerForm(), alice),
        )
    }

    @Test
    fun `the counter customer cannot be archived`() = runTest {
        val counter = customers.counter()

        assertEquals(ArchiveCustomer.Result.CounterProtected, archive(counter.id, archived = true, actor = alice))
        assertFalse(customers.counter().archived)
    }

    @Test
    fun `an archived customer leaves the list and comes back`() = runTest {
        val customer = created(walkIn)

        assertEquals(ArchiveCustomer.Result.Done, archive(customer.id, archived = true, actor = alice))
        assertEquals(emptyList(), customers.search("", archived = false, limit = 10))
        assertEquals(listOf(customer.id), customers.search("", archived = true, limit = 10).map { it.id })
        assertEquals(AuditEvent.CustomerArchived(alice, customer.id, true), audit.events.last())
        assertEquals("customer=${customer.id.value};archived=true", audit.events.last().details)

        archive(customer.id, archived = false, actor = alice)
        assertEquals(1, customers.count(archived = false))
    }

    @Test
    fun `customers are found by name, TIN or phone, without case nor accents`() = runTest {
        created(company)
        created(walkIn.copy(name = "Égide Niyonzima", phone = "79 123 456"))

        suspend fun found(query: String) = customers.search(query, archived = false, limit = 10).map { it.details.name }

        assertEquals(listOf("Brasserie du Lac SA", "Égide Niyonzima"), found(""))
        assertEquals(listOf("Égide Niyonzima"), found("EGIDE"))
        assertEquals(listOf("Brasserie du Lac SA"), found("0002020"))
        assertEquals(listOf("Égide Niyonzima"), found("123 456"))
        assertEquals(emptyList(), found("inconnu"))
    }

    @Test
    fun `a user who may not manage the customers changes none`() = runTest {
        access.denied += Permission.MANAGE_CUSTOMERS

        assertFailsWith<PermissionDeniedException> { create(company, alice) }
        assertFailsWith<PermissionDeniedException> { update(CustomerId(1), company, alice) }
        assertFailsWith<PermissionDeniedException> { archive(CustomerId(1), archived = true, actor = alice) }

        assertEquals(List(3) { alice to Permission.MANAGE_CUSTOMERS }, access.required)
        assertEquals(emptyList(), audit.events)
    }
}
