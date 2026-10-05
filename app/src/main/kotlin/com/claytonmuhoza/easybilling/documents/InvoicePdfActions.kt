package com.claytonmuhoza.easybilling.documents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.claytonmuhoza.easybilling.R
import com.claytonmuhoza.easybilling.core.designsystem.component.StatusBanner
import com.claytonmuhoza.easybilling.core.designsystem.theme.Spacing
import com.claytonmuhoza.easybilling.core.designsystem.theme.StatusTone
import com.claytonmuhoza.easybilling.domain.document.CreateInvoicePdf
import com.claytonmuhoza.easybilling.domain.identity.SessionManager
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.ui.storageCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** How asking for the PDF of an invoice ended. */
enum class PdfOutcome {
    /** The PDF was handed to another application. */
    DONE,

    /** There is no such invoice. */
    NOT_FOUND,

    /** No application of the device can open a PDF. */
    NO_APPLICATION,

    /** The PDF could not be created or written. */
    FAILED,
}

/** Gives the address under which another application may read a file of the application. */
fun interface SharedFileUris {
    /**
     * @param file a file of the folder the application shares.
     * @return its content address.
     */
    fun of(file: File): Uri
}

/**
 * The addresses given by the `FileProvider` declared in the manifest: only the application that
 * receives one may read the file, and only that file.
 *
 * @param context the application.
 */
class FileProviderUris @Inject constructor(@param:ApplicationContext private val context: Context) : SharedFileUris {
    override fun of(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    private companion object {
        /** Suffix of the authority declared in the manifest. */
        const val AUTHORITY_SUFFIX = ".files"
    }
}

/**
 * Hands the PDF of an invoice to another application. The file is written in the cache of the
 * application and reached through a content address that only the receiver may read.
 *
 * @param context the application.
 * @param uris gives the content address of the file.
 */
class InvoicePdfLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val uris: SharedFileUris,
) {

    /**
     * Writes the PDF in the folder of the cache the application shares. A file with the same name
     * is replaced.
     *
     * @param fileName the name of the file.
     * @param bytes the PDF.
     * @return the content address of the file.
     * @throws IOException if the file cannot be written.
     */
    fun write(fileName: String, bytes: ByteArray): Uri {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        val file = File(folder, fileName).apply { writeBytes(bytes) }
        return uris.of(file)
    }

    /**
     * Opens a written PDF in the application the device uses for PDF files.
     *
     * @param pdf the content address of the file.
     * @return false when no application can open a PDF.
     */
    fun open(pdf: Uri): Boolean = started(Intent(Intent.ACTION_VIEW).setDataAndType(pdf, PDF))

    /**
     * Offers to send a written PDF with the applications of the device: messaging, e-mail (UC-G03).
     *
     * @param pdf the content address of the file.
     * @return false when no application can receive it.
     */
    fun share(pdf: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType(PDF).putExtra(Intent.EXTRA_STREAM, pdf)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser reads the address to show a preview: it is given the same right.
        send.clipData = ClipData.newRawUri(null, pdf)
        return started(Intent.createChooser(send, context.getString(R.string.document_share_title)))
    }

    private fun started(intent: Intent): Boolean =
        try {
            // Started from the application, outside any screen.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private companion object {
        const val PDF = "application/pdf"

        /** Folder of the cache declared in `res/xml/file_paths.xml`. */
        const val FOLDER = "invoices"
    }
}

/**
 * Creates the PDF of an invoice and opens or shares it (UC-G02, UC-G03), for the screens that
 * show an invoice.
 *
 * @param createPdf creates the PDF and traces it in the audit log.
 * @param sessions gives the user who asks.
 * @param launcher hands the file to another application.
 */
class InvoicePdfActions @Inject constructor(
    private val createPdf: CreateInvoicePdf,
    private val sessions: SessionManager,
    private val launcher: InvoicePdfLauncher,
) {
    /**
     * @param id the invoice.
     * @return how opening its PDF ended.
     */
    suspend fun open(id: InvoiceId): PdfOutcome = created(id, launcher::open)

    /**
     * @param id the invoice.
     * @return how sharing its PDF ended.
     */
    suspend fun share(id: InvoiceId): PdfOutcome = created(id, launcher::share)

    private suspend fun created(id: InvoiceId, launch: (Uri) -> Boolean): PdfOutcome {
        val actor = sessions.current.value?.userId ?: return PdfOutcome.FAILED
        return when (val pdf = storageCall { withContext(Dispatchers.Default) { createPdf(id, actor) } }.getOrNull()) {
            null -> PdfOutcome.FAILED
            CreateInvoicePdf.Result.NotFound -> PdfOutcome.NOT_FOUND
            is CreateInvoicePdf.Result.Created -> try {
                val file = withContext(Dispatchers.IO) { launcher.write(pdf.fileName, pdf.bytes) }
                // Another application is started from the main thread, like any screen.
                if (launch(file)) PdfOutcome.DONE else PdfOutcome.NO_APPLICATION
            } catch (_: IOException) {
                PdfOutcome.FAILED
            }
        }
    }
}

/**
 * The two things to do with the PDF of an invoice, and what went wrong at the last try.
 *
 * @param onOpen called when the user opens the PDF.
 * @param onShare called when the user shares the PDF.
 * @param problem how the last try ended, or null when nothing is to be said.
 * @param modifier modifier applied to the row of buttons.
 * @param enabled false while a PDF is being created.
 */
@Composable
fun PdfButtons(
    onOpen: () -> Unit,
    onShare: () -> Unit,
    problem: PdfOutcome?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        // Two words each and no icon: both fit side by side on a narrow phone.
        OutlinedButton(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.document_open_pdf))
        }
        OutlinedButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.document_share))
        }
    }
    when (problem) {
        PdfOutcome.NO_APPLICATION -> StatusBanner(StatusTone.PENDING, stringResource(R.string.document_no_application))
        PdfOutcome.FAILED -> StatusBanner(StatusTone.ERROR, stringResource(R.string.document_pdf_failed))
        PdfOutcome.NOT_FOUND -> StatusBanner(StatusTone.ERROR, stringResource(R.string.invoice_not_found))
        PdfOutcome.DONE, null -> Unit
    }
}
