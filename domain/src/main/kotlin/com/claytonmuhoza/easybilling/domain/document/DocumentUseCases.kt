package com.claytonmuhoza.easybilling.domain.document

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceRepository
import com.claytonmuhoza.easybilling.domain.transmission.ReceiptArchive

/**
 * The logo printed at the top of the documents: an image as it was chosen, already reduced.
 *
 * @property bytes the image, as a PNG.
 */
class DocumentLogo(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is DocumentLogo && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "DocumentLogo(${bytes.size} bytes)"
}

/**
 * What the user chooses of the documents (UC-A08). The mandatory mentions are not among it: they
 * can be neither changed nor removed.
 *
 * @property footer a text written at the bottom of every document; may be empty.
 * @property logo the logo, or null.
 * @property showQrCode whether a QR code carrying the identifier of the invoice is printed, so the
 * invoice can be checked at the OBR by scanning it.
 */
data class DocumentSettings(
    val footer: String = "",
    val logo: DocumentLogo? = null,
    val showQrCode: Boolean = false,
) {
    /** Limits of the settings. */
    companion object {
        /** Longest footer, in characters, after trimming. */
        const val MAX_FOOTER_LENGTH = 200
    }
}

/** Storage of the settings of the documents. */
interface DocumentSettingsRepository {
    /** @return the settings; the defaults while none was saved. */
    suspend fun read(): DocumentSettings

    /** @param settings the settings, which replace the saved ones. */
    suspend fun write(settings: DocumentSettings)
}

/**
 * Saves the settings of the documents (UC-A08).
 *
 * @param settings where the settings are kept.
 */
class SaveDocumentSettings(private val settings: DocumentSettingsRepository) {
    /** Outcome of a save. */
    sealed interface Result {
        /** The settings are saved. */
        data object Saved : Result

        /** The footer is longer than [DocumentSettings.MAX_FOOTER_LENGTH]; nothing was saved. */
        data object FooterTooLong : Result
    }

    /**
     * @param wanted the settings to save; the footer is trimmed.
     * @return whether they were saved.
     */
    suspend operator fun invoke(wanted: DocumentSettings): Result {
        val footer = wanted.footer.trim()
        if (footer.length > DocumentSettings.MAX_FOOTER_LENGTH) return Result.FooterTooLong
        settings.write(wanted.copy(footer = footer))
        return Result.Saved
    }
}

/**
 * Builds the document of an invoice from what is stored: the invoice, the tax profile it is sent
 * with, its status, its acknowledgement and the settings of the documents.
 *
 * @param invoices the invoices and their status.
 * @param profiles the versions of the tax profile.
 * @param receipts the acknowledgements of the OBR.
 * @param settings the settings of the documents.
 */
class BuildInvoiceDocument(
    private val invoices: InvoiceRepository,
    private val profiles: TaxProfileRepository,
    private val receipts: ReceiptArchive,
    private val settings: DocumentSettingsRepository,
) {
    /** Outcome of a build. */
    sealed interface Result {
        /** The document of the invoice. */
        data class Built(val document: InvoiceDocument) : Result

        /** There is no such invoice, or its tax profile can no longer be read. */
        data object NotFound : Result
    }

    /**
     * @param id the invoice.
     * @return its document.
     */
    suspend operator fun invoke(id: InvoiceId): Result {
        val invoice = invoices.find(id)
        val status = invoices.statusOf(id)
        val seller = invoice?.let { profiles.find(it.content.profileVersion) }?.profile
        return if (invoice == null || status == null || seller == null) {
            Result.NotFound
        } else {
            Result.Built(InvoiceDocument.of(invoice, seller, status, receipts.find(id), settings.read()))
        }
    }
}

/** Writes a document as a PDF. The domain knows nothing of how a page is drawn. */
fun interface InvoicePdfWriter {
    /**
     * @param document the document.
     * @return the PDF file.
     */
    fun write(document: InvoiceDocument): ByteArray
}

/**
 * Creates the PDF of an invoice (UC-G02), to open or to share (UC-G03). Every copy is the same
 * document and is traced in the audit log (UC-G04).
 *
 * @param build builds the document.
 * @param writer writes it as a PDF.
 * @param auditLog log that records each copy.
 */
class CreateInvoicePdf(
    private val build: BuildInvoiceDocument,
    private val writer: InvoicePdfWriter,
    private val auditLog: AuditLog,
) {
    /** Outcome of a creation. */
    sealed interface Result {
        /**
         * The PDF.
         *
         * @property fileName a name for the file, after the number of the invoice.
         * @property bytes the file.
         */
        class Created(val fileName: String, val bytes: ByteArray) : Result

        /** There is no such invoice. */
        data object NotFound : Result
    }

    /**
     * @param id the invoice.
     * @param actor the user who asks for the PDF.
     * @return the PDF, or why there is none.
     */
    suspend operator fun invoke(id: InvoiceId, actor: UserId): Result =
        when (val built = build(id)) {
            BuildInvoiceDocument.Result.NotFound -> Result.NotFound
            is BuildInvoiceDocument.Result.Built -> {
                val bytes = writer.write(built.document)
                auditLog.record(AuditEvent.InvoicePdfCreated(actor, id))
                Result.Created("Facture-${built.document.number.value.replace(UNSAFE, "-")}.pdf", bytes)
            }
        }

    private companion object {
        /** What a file name does not carry everywhere. */
        val UNSAFE = Regex("[^A-Za-z0-9_-]+")
    }
}
