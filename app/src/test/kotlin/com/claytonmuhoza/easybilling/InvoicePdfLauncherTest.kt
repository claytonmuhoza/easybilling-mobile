package com.claytonmuhoza.easybilling

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.documents.InvoicePdfLauncher
import com.claytonmuhoza.easybilling.documents.SharedFileUris
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Handing the PDF of an invoice to another application (UC-G02, UC-G03). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class InvoicePdfLauncherTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val uris = SharedFileUris { file -> Uri.parse("content://test.files/invoices/${file.name}") }
    private val launcher = InvoicePdfLauncher(application, uris)
    private val pdf = "%PDF".toByteArray()

    private fun granted(intent: Intent) =
        intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == Intent.FLAG_GRANT_READ_URI_PERMISSION

    @Test
    fun `opening writes the file in the shared folder and asks for a PDF viewer`() {
        val written = launcher.write("Facture-FN-00001-2026.pdf", pdf)
        assertTrue(launcher.open(written))

        assertEquals("%PDF", File(application.cacheDir, "invoices/Facture-FN-00001-2026.pdf").readText())
        val view = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("application/pdf", view.type)
        assertEquals("content://test.files/invoices/Facture-FN-00001-2026.pdf", view.dataString)
        assertTrue(granted(view))
    }

    @Test
    fun `sharing offers the applications of the device the file, readable by the one chosen`() {
        assertTrue(launcher.share(launcher.write("Facture-FN-00001-2026.pdf", pdf)))

        val chooser = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        val stream = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content://test.files/invoices/Facture-FN-00001-2026.pdf", stream.toString())
        assertTrue(granted(send))
    }

    @Test
    fun `a second copy replaces the file of the first`() {
        launcher.write("Facture-FN-00001-2026.pdf", pdf)
        launcher.write("Facture-FN-00001-2026.pdf", "%PDF again".toByteArray())

        assertEquals(listOf("Facture-FN-00001-2026.pdf"), File(application.cacheDir, "invoices").list()?.toList())
        assertEquals("%PDF again", File(application.cacheDir, "invoices/Facture-FN-00001-2026.pdf").readText())
    }

    @Test
    fun `without an application that opens a PDF, opening says so`() {
        // Starting an application the device does not have fails, as on a real device.
        shadowOf(application).checkActivities(true)

        assertFalse(launcher.open(launcher.write("Facture-FN-00001-2026.pdf", pdf)))
    }
}
