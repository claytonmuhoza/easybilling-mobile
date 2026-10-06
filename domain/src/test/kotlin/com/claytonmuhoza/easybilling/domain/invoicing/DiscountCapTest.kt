package com.claytonmuhoza.easybilling.domain.invoicing

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.identity.Credentials
import com.claytonmuhoza.easybilling.domain.identity.NewUser
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.Role
import com.claytonmuhoza.easybilling.domain.identity.SecretHash
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The discount a cashier gives alone, and the approval of a manager beyond it (UC-C03, UC-H04).
 * Alice, who issues the sales of the fixture, is a cashier.
 */
class DiscountCapTest {

    private val fixture = SaleFixture()
    private val limits = fixture.discountLimits
    private val alice = fixture.alice

    private fun bif(amount: String) = Money.of(amount, Currency.BIF)

    private fun percent(rate: String) = Discount.Percent(BigDecimal(rate))

    private suspend fun account(vararg roles: Role): UserId {
        val secret = Credentials(SecretHash(byteArrayOf(1), byteArrayOf(1), 1), recoveryHash = null)
        return fixture.users.create(NewUser("User ${fixture.users.count() + 1}", roles.toSet(), secret)).id
    }

    private suspend fun capCashierAt(rate: String) {
        assertEquals(SaveDiscountSettings.Result.Saved, fixture.saveDiscountSettings(BigDecimal(rate), alice))
    }

    /** A sale of one soap at 1 000 with [discount]; [approval] is given with it, if any. */
    private suspend fun discountedSale(discount: Discount, approval: DiscountApproval? = null): SaleDraft {
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        fixture.editSale.addItem(soap.id)
        val line = SaleLineCheck.Valid(Quantity.of(BigDecimal.ONE), discount, emptyMap())
        return fixture.editSale.setLine(soap.id, line, approval)
    }

    @Test
    fun `a discount is measured as a share of the list price, whatever its kind`() {
        assertEquals(0, BigDecimal("12.5").compareTo(percent("12.5").rateOf(bif("1000"))))
        assertEquals(0, BigDecimal("15").compareTo(Discount.PerUnit(BigDecimal("150")).rateOf(bif("1000"))))
        // Rounded up to the hundredth: a discount just above a cap is never taken for one within it.
        assertEquals(0, BigDecimal("33.34").compareTo(Discount.PerUnit(BigDecimal("1")).rateOf(bif("3"))))
    }

    @Test
    fun `without a cap, which is the default, no discount needs an approval`() = runTest {
        fixture.ready()

        assertNull(fixture.discountSettings.read().cashierCap)
        assertEquals(false, limits.needsApproval(alice, percent("90"), bif("1000")))
    }

    @Test
    fun `beyond the cap of the cashier, a discount needs an approval`() = runTest {
        fixture.ready()
        capCashierAt("10")

        assertEquals(false, limits.needsApproval(alice, percent("10"), bif("1000")))
        assertEquals(true, limits.needsApproval(alice, percent("10.5"), bif("1000")))
        assertEquals(true, limits.needsApproval(alice, Discount.PerUnit(BigDecimal("150")), bif("1000")))
        assertEquals(false, limits.needsApproval(alice, null, bif("1000")))
    }

    @Test
    fun `an account allowed to approve has no cap, whatever its other roles`() = runTest {
        fixture.ready()
        capCashierAt("0")

        assertEquals(false, limits.needsApproval(account(Role.MANAGER), percent("50"), bif("1000")))
        assertEquals(false, limits.needsApproval(account(Role.CASHIER, Role.MANAGER), percent("50"), bif("1000")))
        assertEquals(false, limits.needsApproval(account(Role.ADMINISTRATOR), percent("50"), bif("1000")))
        assertEquals(true, limits.needsApproval(account(Role.CASHIER, Role.STOREKEEPER), percent("1"), bif("1000")))
    }

    @Test
    fun `the cap is between 0 and 100, saved by who manages the users, and audited`() = runTest {
        fixture.ready()

        assertEquals(SaveDiscountSettings.Result.OutOfRange, fixture.saveDiscountSettings(BigDecimal("100"), alice))
        assertEquals(SaveDiscountSettings.Result.OutOfRange, fixture.saveDiscountSettings(BigDecimal("-1"), alice))
        assertEquals(
            SaveDiscountSettings.Result.OutOfRange,
            fixture.saveDiscountSettings(BigDecimal("10.555"), alice),
        )
        capCashierAt("12.5")
        assertEquals(0, BigDecimal("12.5").compareTo(fixture.discountSettings.read().cashierCap))
        assertEquals(AuditEvent.DiscountCapChanged(alice, BigDecimal("12.5")), fixture.audit.events.last())

        assertEquals(SaveDiscountSettings.Result.Saved, fixture.saveDiscountSettings(null, alice))
        assertNull(fixture.discountSettings.read().cashierCap)

        fixture.access.denied += Permission.MANAGE_USERS
        assertFailsWith<PermissionDeniedException> { fixture.saveDiscountSettings(BigDecimal("5"), alice) }
        assertNull(fixture.discountSettings.read().cashierCap)
    }

    @Test
    fun `an approval stays with its discount and is lost when the discount changes`() = runTest {
        fixture.ready()
        val marc = account(Role.MANAGER)
        val approval = DiscountApproval(marc, percent("25"))

        val approved = discountedSale(percent("25"), approval)
        assertEquals(approval, approved.lines.single().approval)

        // The quantity changes, not the discount: the approval holds.
        val soap = approved.lines.single().itemId
        val more = SaleLineCheck.Valid(Quantity.of(BigDecimal("2")), percent("25"), emptyMap())
        assertEquals(approval, fixture.editSale.setLine(soap, more).lines.single().approval)

        val changed = SaleLineCheck.Valid(Quantity.of(BigDecimal("2")), percent("30"), emptyMap())
        assertNull(fixture.editSale.setLine(soap, changed).lines.single().approval)
    }

    @Test
    fun `an approval given for another discount is not kept`() = runTest {
        fixture.ready()
        val marc = account(Role.MANAGER)

        val draft = discountedSale(percent("30"), DiscountApproval(marc, percent("25")))

        assertNull(draft.lines.single().approval)
    }

    @Test
    fun `a discount beyond the cap without approval is not issued, and no number is consumed`() = runTest {
        fixture.ready()
        capCashierAt("10")
        val draft = discountedSale(percent("25"))

        val result = fixture.issueSale(draft.id, alice)

        val refused = assertIs<IssueSale.Result.DiscountNotApproved>(result)
        assertEquals(draft.lines.map { it.itemId }.toSet(), refused.itemIds)
        assertEquals(draft.id, fixture.drafts.find(draft.id)?.id)
        val next = discountedSale(percent("5"))
        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(next.id, alice)).invoice
        assertEquals("FN-00001/2026", invoice.content.number)
    }

    @Test
    fun `a discount beyond the cap with the approval of a manager is issued`() = runTest {
        fixture.ready()
        capCashierAt("10")
        val marc = account(Role.MANAGER)
        val draft = discountedSale(percent("25"), DiscountApproval(marc, percent("25")))

        val invoice = assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, alice)).invoice

        assertEquals(percent("25"), invoice.content.lines.single().discount)
    }
}
