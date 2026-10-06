package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cap of the discount of the cashier (UC-C03, D50), on a real database file. */
@RunWith(AndroidJUnit4::class)
class DiscountSettingsStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name, and short test names: see RoomItemRepositoryTest.
    private val name = "discounts.db"
    private var storage = LocalStorage.unencryptedFile(context, name)

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    private fun restart() {
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)
    }

    @Test
    fun `a new device has no cap`() = runTest {
        assertEquals(DiscountSettings(), storage.discountSettings.read())
    }

    @Test
    fun `the cap is read back exactly after a restart`() = runTest {
        storage.discountSettings.write(DiscountSettings(cashierCap = BigDecimal("12.5")))
        restart()

        assertEquals(DiscountSettings(cashierCap = BigDecimal("12.5")), storage.discountSettings.read())
    }

    @Test
    fun `the cap can be taken away`() = runTest {
        storage.discountSettings.write(DiscountSettings(cashierCap = BigDecimal("10")))
        storage.discountSettings.write(DiscountSettings())
        restart()

        assertEquals(DiscountSettings(), storage.discountSettings.read())
    }
}
