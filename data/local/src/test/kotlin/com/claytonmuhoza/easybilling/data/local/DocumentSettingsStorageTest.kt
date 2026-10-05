package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/** The settings of the documents (UC-A08), on a real database file. */
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
    fun `saving again replaces the settings, and the logo can be removed`() = runTest {
        storage.documentSettings.write(DocumentSettings("Merci", DocumentLogo(byteArrayOf(1, 2)), showQrCode = true))

        storage.documentSettings.write(DocumentSettings(footer = "À bientôt"))

        assertEquals(DocumentSettings(footer = "À bientôt"), storage.documentSettings.read())
    }
}
