package com.claytonmuhoza.easybilling.platform.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.document.DocumentField
import com.claytonmuhoza.easybilling.domain.document.DocumentLine
import com.claytonmuhoza.easybilling.domain.document.InvoiceDocument
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PDF file of an invoice, written by the PDF support of a real device: the JVM tests draw the
 * pages but cannot write the file.
 */
@RunWith(AndroidJUnit4::class)
class InvoicePdfFileTest {

    private fun field(label: String, value: String) = DocumentField(null, label, value)

    private fun document(lines: Int) = InvoiceDocument(
        title = "FACTURE",
        number = field("Facture n°", "FN-00048/2026"),
        date = field("Date", "03/10/2026 10:15:42"),
        identifier = field("Identifiant", IDENTIFIER),
        notice = null,
        seller = listOf(field("Vendeur", "Boutique Umuco SPRL"), field("NIF", "4000000001")),
        customer = listOf(field("Client", "Client comptoir")),
        payment = listOf(field("Paiement", "Espèces")),
        columns = listOf("Désignation", "Qté", "P.U. HT", "TC", "PFL", "HTVA", "TVA", "TVAC", "Total"),
        lines = List(lines) { rank ->
            val cells = listOf("Article $rank", "1", "1 000", "0", "0", "1 000", "180", "1 180", "1 180")
            DocumentLine(cells.map { field("", it) })
        },
        totals = listOf(field("Total à payer", "1 180 BIF")),
        obr = emptyList(),
        footer = "Merci de votre visite",
        logo = null,
        qrContent = IDENTIFIER,
    )

    @Test
    fun theWriterGivesAPdfFile() {
        val bytes = AndroidInvoicePdfWriter().write(document(lines = 2))

        assertEquals("%PDF", bytes.take(4).toByteArray().decodeToString())
        assertTrue(bytes.size > 1_000, "${bytes.size} bytes")
    }

    @Test
    fun aLongInvoiceIsWrittenOnSeveralPages() {
        val short = AndroidInvoicePdfWriter().write(document(lines = 2))
        val long = AndroidInvoicePdfWriter().write(document(lines = 120))

        assertEquals("%PDF", long.take(4).toByteArray().decodeToString())
        assertTrue(long.size > short.size, "${long.size} bytes against ${short.size}")
    }

    private companion object {
        const val IDENTIFIER = "4000000001/ws400000000100001/20261003101542/FN-00048/2026"
    }
}
