package com.claytonmuhoza.easybilling.domain.catalog

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileCheck
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.testing.DirectTransactionRunner
import com.claytonmuhoza.easybilling.domain.testing.InMemoryItemRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryNumberingRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxProfileRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryTaxRepository
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import com.claytonmuhoza.easybilling.domain.testing.TaxProfileSamples
import com.claytonmuhoza.easybilling.domain.testing.percent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Creating, changing, archiving and finding the items of the catalogue (UC-B01, B04, B07). */
class ItemUseCasesTest {

    private val items = InMemoryItemRepository()
    private val profiles = InMemoryTaxProfileRepository()
    private val numbering = InMemoryNumberingRepository()
    private val taxes = InMemoryTaxRepository()
    private val audit = RecordingAuditLog()
    private val rules = ReadItemRules(profiles, numbering, taxes)
    private val create = CreateItem(items, rules, audit, DirectTransactionRunner)
    private val update = UpdateItem(items, rules, audit, DirectTransactionRunner)
    private val archive = ArchiveItem(items, audit, DirectTransactionRunner)
    private val suggest = SuggestItemCode(items)
    private val alice = UserId(1)

    private val form = ItemForm(
        code = "ART-0001",
        designation = "Savon de Marseille",
        unit = "pièce",
        price = "1500",
        priceMode = PriceMode.INCLUDING_TAXES,
        stockTracked = true,
    )

    private suspend fun created(form: ItemForm) = assertIs<CreateItem.Result.Created>(create(form, alice)).item

    private suspend fun saveProfile(vatTaxpayer: Boolean) {
        val profileForm = TaxProfileSamples.form().copy(taxes = TaxRegistrations(vat = vatTaxpayer))
        val profile = assertIs<TaxProfileCheck.Valid>(profileForm.check()).profile
        val number = (profiles.current()?.number ?: 0) + 1
        profiles.append(TaxProfileVersion(number, profile, Instant.parse("2026-10-03T08:00:00Z"), alice))
    }

    private suspend fun severalDevices(code: String) {
        val numberingForm = NumberingForm.defaultFor(multipleDevices = true).copy(deviceCode = code)
        numbering.save(assertIs<NumberingCheck.Valid>(numberingForm.check(null)).settings)
    }

    private suspend fun vat() = taxes.create(
        TaxKind.VAT,
        "TVA 18 %",
        TaxVersion(LocalDate.of(2026, 10, 3), percent("18", PercentageBase.PRICE_WITHOUT_VAT)),
    )

    @Test
    fun `an item is created and audited`() = runTest {
        val item = created(form)

        assertEquals("ART-0001", item.code)
        assertEquals(Money.of("1500", Currency.BIF), item.details.price)
        assertEquals(item, items.find(item.id))
        assertEquals(AuditEvent.ItemCreated(alice, item.id, "ART-0001"), audit.events.single())
        assertEquals("item=${item.id.value};code=ART-0001", audit.events.single().details)
    }

    @Test
    fun `an invalid item is refused and nothing is stored`() = runTest {
        val result = create(form.copy(designation = ""), alice)

        assertEquals(CreateItem.Result.Invalid(setOf(ItemProblem.DESIGNATION_MISSING)), result)
        assertEquals(0, items.count(archived = false))
        assertEquals(emptyList(), audit.events)
    }

    @Test
    fun `two items never share a code, whatever the case and even when one is archived`() = runTest {
        val first = created(form)
        archive(first.id, true, alice)

        val result = create(form.copy(code = "art-0001", designation = "Autre savon"), alice)

        assertEquals(CreateItem.Result.Invalid(setOf(ItemProblem.CODE_TAKEN)), result)
    }

    @Test
    fun `the rules follow the tax profile, the numbering and the taxes`() = runTest {
        assertEquals(ItemRules(TaxRegistrations(), devicePrefix = null, taxes = emptyList()), rules())

        saveProfile(vatTaxpayer = true)
        severalDevices("A1")
        val tax = vat()

        assertEquals(ItemRules(TaxRegistrations(vat = true), devicePrefix = "A1", taxes = listOf(tax)), rules())
    }

    @Test
    fun `with several devices an item without the device code is refused`() = runTest {
        severalDevices("A1")

        assertEquals(CreateItem.Result.Invalid(setOf(ItemProblem.CODE_DEVICE_PREFIX_MISSING)), create(form, alice))
        assertEquals("A1-ART-0001", created(form.copy(code = "A1-ART-0001")).code)
    }

    @Test
    fun `the suggested code follows the number of items and skips the codes already taken`() = runTest {
        assertEquals("ART-0001", suggest(devicePrefix = null))
        created(form)
        created(form.copy(code = "ART-0003"))

        // Two items exist: ART-0003 is the next rank, but it is taken.
        assertEquals("ART-0004", suggest(devicePrefix = null))
        assertEquals("A1-ART-0003", suggest(devicePrefix = "A1"))
    }

    @Test
    fun `an item is changed without touching its code, and audited`() = runTest {
        val item = created(form)

        val result = update(item.id, form.copy(code = "OTHER", designation = "Savon noir", price = "2000"), alice)

        assertEquals(UpdateItem.Result.Updated, result)
        val stored = items.find(item.id)!!
        assertEquals("ART-0001", stored.code)
        assertEquals("Savon noir", stored.details.designation)
        assertEquals(Money.of("2000", Currency.BIF), stored.details.price)
        assertEquals(AuditEvent.ItemUpdated(alice, item.id), audit.events.last())
    }

    @Test
    fun `an invalid change or an unknown item changes nothing`() = runTest {
        val item = created(form)

        assertEquals(
            UpdateItem.Result.Invalid(setOf(ItemProblem.PRICE_INVALID)),
            update(item.id, form.copy(price = "0"), alice),
        )
        assertEquals(UpdateItem.Result.NotFound, update(ItemId(99), form, alice))
        assertEquals(item, items.find(item.id))
    }

    @Test
    fun `an item created without VAT is completed after the business becomes registered for the VAT`() = runTest {
        saveProfile(vatTaxpayer = false)
        val item = created(form)
        assertEquals(1, items.countWithoutVat())

        saveProfile(vatTaxpayer = true)
        val tax = vat()

        // The item is kept as it is, but it now misses its VAT and cannot be saved without one.
        assertEquals(true, items.find(item.id)!!.needsVat(rules().registrations))
        assertEquals(UpdateItem.Result.Invalid(setOf(ItemProblem.VAT_MISSING)), update(item.id, form, alice))

        assertEquals(UpdateItem.Result.Updated, update(item.id, form.copy(taxes = mapOf(TaxKind.VAT to tax.id)), alice))
        assertEquals(false, items.find(item.id)!!.needsVat(rules().registrations))
        assertEquals(0, items.countWithoutVat())
    }

    @Test
    fun `an archived item is kept, leaves the active list and can come back`() = runTest {
        val item = created(form)

        assertEquals(ArchiveItem.Result.Done, archive(item.id, true, alice))

        assertEquals(emptyList(), items.search("", archived = false, limit = 10))
        assertEquals(listOf(item.copy(archived = true)), items.search("", archived = true, limit = 10))
        assertEquals(AuditEvent.ItemArchived(alice, item.id, true), audit.events.last())

        archive(item.id, false, alice)
        assertEquals(listOf(item), items.search("savon", archived = false, limit = 10))
        assertEquals(ArchiveItem.Result.NotFound, archive(ItemId(99), true, alice))
        assertNull(items.find(ItemId(99)))
    }

    @Test
    fun `two items never share a barcode, even when one is archived`() = runTest {
        val first = created(form.copy(barcode = "6001234567890"))
        archive(first.id, true, alice)

        val result = create(form.copy(code = "ART-0002", barcode = " 6001234567890 "), alice)

        assertEquals(CreateItem.Result.Invalid(setOf(ItemProblem.BARCODE_TAKEN)), result)
        // Items without a barcode never collide.
        created(form.copy(code = "ART-0002"))
        created(form.copy(code = "ART-0003"))
    }

    @Test
    fun `the barcode of an item can change, but not to the barcode of another item`() = runTest {
        val soap = created(form.copy(barcode = "111"))
        val other = created(form.copy(code = "ART-0002", barcode = "222"))

        // Saving an item again with its own barcode is not a collision.
        assertEquals(UpdateItem.Result.Updated, update(soap.id, form.copy(barcode = "111", price = "1600"), alice))
        assertEquals(UpdateItem.Result.Updated, update(soap.id, form.copy(barcode = "333"), alice))
        assertEquals("333", items.find(soap.id)!!.details.barcode)

        assertEquals(
            UpdateItem.Result.Invalid(setOf(ItemProblem.BARCODE_TAKEN)),
            update(other.id, form.copy(barcode = "333"), alice),
        )
        assertEquals(UpdateItem.Result.Updated, update(other.id, form.copy(barcode = ""), alice))
        assertNull(items.find(other.id)!!.details.barcode)
    }

    @Test
    fun `an item is found by its barcode`() = runTest {
        val soap = created(form.copy(barcode = "6001234567890"))
        created(form.copy(code = "ART-0002", designation = "Savon noir"))

        assertEquals(listOf(soap), items.search("6001234567890", archived = false, limit = 10))
        assertEquals(listOf(soap), items.search("12345", archived = false, limit = 10))
        assertEquals(soap, items.findByBarcode("6001234567890"))
        assertNull(items.findByBarcode("999"))
    }
}
