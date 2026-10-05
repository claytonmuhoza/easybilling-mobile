package com.claytonmuhoza.easybilling

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.documents.FileProviderUris
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `FileProvider` of the manifest and its shared folder (UC-G03): the address it gives, the
 * file read back through it, and its refusal of a file outside the shared folder.
 *
 * `FileProvider` compares paths with `/`: on a JVM running on Windows it finds no file, so
 * these tests only run elsewhere, which includes the continuous integration.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FileProviderUrisTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val uris = FileProviderUris(application)

    private fun notOnWindows() =
        assumeFalse("FileProvider cannot match Windows paths", File.separatorChar == '\\')

    @Test
    fun `a file of the shared folder has a content address and is read through it`() {
        notOnWindows()
        val file = File(application.cacheDir, "invoices").apply { mkdirs() }.resolve("Facture-FN-00001-2026.pdf")
        file.writeText("%PDF")

        val uri = uris.of(file)

        assertEquals("content://com.claytonmuhoza.easybilling.files/invoices/Facture-FN-00001-2026.pdf", uri.toString())
        assertEquals("%PDF", application.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.decodeToString())
    }

    @Test
    fun `a file outside the shared folder has no address`() {
        notOnWindows()
        val file = File(application.filesDir, "secret.txt").apply { writeText("secret") }

        assertFailsWith<IllegalArgumentException> { uris.of(file) }
    }
}
