package com.claytonmuhoza.easybilling.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxId
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The tax definitions and their dated rates, on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class RoomTaxRepositoryTest {

    private val storage = LocalStorage.inMemory(ApplicationProvider.getApplicationContext())
    private val taxes = storage.taxes
    private val day = LocalDate.of(2026, 10, 3)

    @After
    fun closeDatabase() = storage.close()

    @Test
    fun `a new database has no tax`() = runTest {
        assertEquals(emptyList(), taxes.all())
        assertNull(taxes.find(TaxId(1)))
    }

    @Test
    fun `every way of computing is read back exactly`() = runTest {
        val computations = listOf(
            percent("16.5", PercentageBase.PRICE_WITHOUT_VAT),
            percent("1.25", PercentageBase.PRICE),
            percent("0", PercentageBase.PRICE_WITH_VAT),
            perUnit("2.75"),
            TaxComputation.FreeAmount,
        )
        val created = computations.mapIndexed { index, computation ->
            taxes.create(TaxKind.FLAT_RATE_WITHHOLDING, "Taxe $index", TaxVersion(day, computation))
        }

        assertEquals(created, taxes.all())
        assertEquals(computations, taxes.all().map { it.latest.computation })
        assertEquals("16.5", (taxes.all().first().latest.computation as TaxComputation.Percentage).rate.toPlainString())
    }

    @Test
    fun `a tax is read back with its kind, name and rates`() = runTest {
        val first = TaxVersion(day, percent("18", PercentageBase.PRICE_WITHOUT_VAT))
        val tax = taxes.create(TaxKind.VAT, "TVA 18 %", first)
        val later = TaxVersion(LocalDate.of(2027, 1, 1), percent("16", PercentageBase.PRICE_WITHOUT_VAT))

        taxes.addVersion(tax.id, later)

        val read = taxes.find(tax.id)!!
        assertEquals(TaxKind.VAT, read.kind)
        assertEquals("TVA 18 %", read.name)
        assertEquals(listOf(tax.versions.single(), later), read.versions)
        assertEquals(false, read.archived)
    }

    @Test
    fun `a tax is archived and brought back`() = runTest {
        val tax = taxes.create(TaxKind.OTT, "OTT", TaxVersion(day, TaxComputation.FreeAmount))

        taxes.setArchived(tax.id, true)
        assertEquals(true, taxes.find(tax.id)!!.archived)

        taxes.setArchived(tax.id, false)
        assertEquals(false, taxes.find(tax.id)!!.archived)
    }

    @Test
    fun `two rates of a tax cannot start the same day`() = runTest {
        val tax = taxes.create(TaxKind.TSCE, "TSCE", TaxVersion(day, TaxComputation.FreeAmount))

        assertFailsWith<Exception> { taxes.addVersion(tax.id, TaxVersion(day, TaxComputation.PerUnit(BigDecimal.ONE))) }
        assertEquals(1, taxes.find(tax.id)!!.versions.size)
    }

    @Test
    fun `the database refuses to modify or delete a rate`() = runTest {
        val tax = taxes.create(TaxKind.CONSUMPTION_TAX, "TC", TaxVersion(day, perUnit("3")))
        val db = storage.database.openHelper.writableDatabase

        assertFailsWith<Exception> { db.execSQL("UPDATE tax_versions SET value = '5'") }
        assertFailsWith<Exception> { db.execSQL("DELETE FROM tax_versions") }
        assertEquals(perUnit("3"), taxes.find(tax.id)!!.latest.computation)
    }
}
