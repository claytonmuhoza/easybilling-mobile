package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import com.claytonmuhoza.easybilling.domain.document.PairedPrinter
import com.claytonmuhoza.easybilling.domain.document.PrinterSettings
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith

/** The settings of the documents and of the printer (UC-A08, UC-A09), on a real database file. */
@RunWith(AndroidJUnit4::class)
class DocumentSettingsStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A short name, and short test names: see RoomItemRepositoryTest.
    private val name = "documents.db"
    private var storage = LocalStorage.unencryptedFile(context, name)

    @After
    fun cleanUp() {
        storage.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `a new device has no footer, no logo and no QR code`() = runTest {
        assertEquals(DocumentSettings(), storage.documentSettings.read())
    }

    @Test
    fun `the settings are read back, logo included, after a restart`() = runTest {
        val logo = DocumentLogo(ByteArray(4_000) { (it % 251).toByte() })
        val settings = DocumentSettings(footer = "Merci de votre visite", logo = logo, showQrCode = true)

        storage.documentSettings.write(settings)
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)

        assertEquals(settings, storage.documentSettings.read())
    }

    @Test
    fun `a new device has no printer and prints on 58 mm without accents`() = runTest {
        assertEquals(PrinterSettings(), storage.printerSettings.read())
        assertEquals(ReceiptWidth.MM_58, storage.printerSettings.read().width)
    }

    @Test
    fun `the printer and how it prints are read back after a restart`() = runTest {
        val settings = PrinterSettings(
            printer = PairedPrinter("00:11:22:33:44:55", "POS-80"),
            width = ReceiptWidth.MM_80,
            accents = true,
            autoPrint = true,
        )

        storage.printerSettings.write(settings)
        storage.close()
        storage = LocalStorage.unencryptedFile(context, name)

        assertEquals(settings, storage.printerSettings.read())
    }

    @Test
    fun `the printer can be dissociated`() = runTest {
        storage.printerSettings.write(PrinterSettings(printer = PairedPrinter("00:11:22:33:44:55", "POS-80")))

        storage.printerSettings.write(PrinterSettings())

        assertEquals(PrinterSettings(), storage.printerSettings.read())
    }

    @Test
    fun `saving again replaces the settings, and the logo can be removed`() = runTest {
        storage.documentSettings.write(DocumentSettings("Merci", DocumentLogo(byteArrayOf(1, 2)), showQrCode = true))

        storage.documentSettings.write(DocumentSettings(footer = "À bientôt"))

        assertEquals(DocumentSettings(footer = "À bientôt"), storage.documentSettings.read())
    }
}
