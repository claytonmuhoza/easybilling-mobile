package com.claytonmuhoza.easybilling.domain.document

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.testing.FakeReceiptPrinter
import com.claytonmuhoza.easybilling.domain.testing.InMemoryDocumentSettingsRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryPrinterSettingsRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryReceiptArchive
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ticket of an invoice (UC-G01) and the printer it goes to (UC-A09): the same mentions as the
 * PDF, laid out in lines of 32 or 48 characters.
 */
class ReceiptTest {

    private val fixture = SaleFixture()
    private val receipts = InMemoryReceiptArchive()
    private val documentSettings = InMemoryDocumentSettingsRepository()
    private val printerSettings = InMemoryPrinterSettingsRepository()
    private val printer = FakeReceiptPrinter()
    private val build = BuildInvoiceDocument(fixture.invoices, fixture.profiles, receipts, documentSettings)
    private val print = PrintInvoice(build, printerSettings, printer, fixture.audit)
    private val testPage = PrintTestPage(printerSettings, printer)

    private val associated = PrinterSettings(printer = PairedPrinter("00:11:22:33:44:55", "POS-58"))

    /** Issues an invoice of two soaps with a long designation. */
    private suspend fun issued(): Invoice {
        if (fixture.profiles.current() == null) fixture.ready()
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        fixture.editSale.addItem(soap.id)
        val draft = fixture.editSale.addItem(soap.id)
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
    }

    private suspend fun documentOf(invoice: Invoice): InvoiceDocument =
        assertIs<BuildInvoiceDocument.Result.Built>(build(invoice.id)).document

    private fun Receipt.text() = lines.joinToString("\n") { it.text }

    @Test
    fun `the ticket carries every mandatory mention of the document, on lines that fit the paper`() = runTest {
        val document = documentOf(issued())

        for (width in ReceiptWidth.entries) {
            val receipt = Receipt.of(document, width, accents = true)

            assertEquals(emptyList(), receipt.lines.filter { it.text.length > width.columns }, "$width")
            // A value is cut where the paper ends: it is looked for without spaces nor line breaks.
            val text = receipt.text().filterNot { it.isWhitespace() }
            val mandatory = document.fields().filter { it.obrName in InvoiceDocument.MANDATORY_FIELDS } +
                document.lines.flatMap { it.cells }
            val missing = mandatory.filter { field -> field.value.filterNot { it.isWhitespace() } !in text }
            assertEquals(emptyList(), missing.map { it.obrName }, "$width")
        }
    }

    @Test
    fun `a line of the invoice gives its designation, quantity times price, total and taxes`() = runTest {
        val receipt = Receipt.of(documentOf(issued()), ReceiptWidth.MM_58, accents = true)
        val lines = receipt.lines.map { it.text }

        val designation = lines.indexOf("Article ART-0001")
        assertTrue(designation > 0, receipt.text())
        assertEquals("2 x 1 000                  2 360", lines[designation + 1])
        assertEquals("  TC 0  PFL 0  HTVA 2 000", lines[designation + 2])
        assertEquals("  TVA 360  TVAC 2 360", lines[designation + 3])
        // The amount to pay stands out, at the end of the totals.
        val total = receipt.lines.single { it.style == ReceiptStyle.TOTAL }
        assertEquals("Total à payer    2 360 BIF", total.text)
    }

    @Test
    fun `the head is centred, and an invoice that is not registered says so`() = runTest {
        val invoice = issued()

        val pending = Receipt.of(documentOf(invoice), ReceiptWidth.MM_80, accents = true)

        assertEquals(ReceiptLine("FACTURE", ReceiptStyle.TITLE, centred = true), pending.lines.first())
        assertEquals(ReceiptLine("Facture n° FN-00001/2026", centred = true), pending.lines[1])
        assertTrue(ReceiptLine("En cours de transmission à l'OBR", ReceiptStyle.BOLD, centred = true) in pending.lines)

        receipts.archive(invoice.id, ObrReceipt("4530253", "2026-10-03 10:04:01", "s", "{}"), fixture.time.now())
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())
        val registered = Receipt.of(documentOf(invoice), ReceiptWidth.MM_80, accents = true)

        assertFalse("En cours" in registered.text())
        assertTrue("N° d'enregistrement OBR : 4530253" in registered.text())
    }

    @Test
    fun `without the accents option every character is plain, and the footer is printed`() = runTest {
        documentSettings.write(DocumentSettings(footer = "Merci – à bientôt", showQrCode = true))

        val plain = Receipt.of(documentOf(issued()), ReceiptWidth.MM_58, accents = false)

        assertEquals(emptyList(), plain.text().filter { it.code > ASCII_LAST && it != '\n' }.toList().distinct())
        assertTrue("Total a payer" in plain.text())
        assertTrue("Facture n. FN-00001/2026" in plain.text(), plain.text())
        assertEquals(ReceiptLine("Merci - a bientot", centred = true), plain.lines.last())
        // No QR code and no logo on a ticket, whatever the settings of the PDF.
        assertFalse(plain.accents)
    }

    @Test
    fun `printing sends the ticket to the associated printer and is audited`() = runTest {
        val invoice = issued()
        printerSettings.write(associated.copy(width = ReceiptWidth.MM_80, accents = true))
        fixture.audit.events.clear()

        assertEquals(PrintInvoice.Result.Printed, print(invoice.id, fixture.alice))

        val (address, receipt) = printer.printed.single()
        assertEquals("00:11:22:33:44:55", address)
        assertEquals(ReceiptWidth.MM_80, receipt.width)
        assertTrue(receipt.accents)
        assertTrue(invoice.content.identifier in receipt.text().filterNot { it.isWhitespace() })
        assertEquals(AuditEvent.InvoicePrinted(fixture.alice, invoice.id), fixture.audit.events.single())
        assertEquals("INVOICE_PRINTED", fixture.audit.events.single().action)
    }

    @Test
    fun `nothing is printed nor audited without a printer, an invoice or an answer of the printer`() = runTest {
        val invoice = issued()
        fixture.audit.events.clear()

        assertEquals(PrintInvoice.Result.NoPrinter, print(invoice.id, fixture.alice))

        printerSettings.write(associated)
        assertEquals(PrintInvoice.Result.NotFound, print(InvoiceId(99), fixture.alice))
        printer.outcome = PrintOutcome.UNREACHABLE
        assertEquals(PrintInvoice.Result.Failed(PrintOutcome.UNREACHABLE), print(invoice.id, fixture.alice))

        assertEquals(emptyList(), printer.printed)
        assertEquals(emptyList(), fixture.audit.events)
    }

    @Test
    fun `the test page shows the width, the accents and the name of the printer`() = runTest {
        assertEquals(PrintOutcome.NO_PRINTER, testPage())

        printerSettings.write(associated)
        assertEquals(PrintOutcome.PRINTED, testPage())

        val page = printer.printed.single().second
        assertEquals(ReceiptWidth.MM_58, page.width)
        assertTrue("POS-58" in page.text())
        assertTrue("1".repeat(1) in page.text())
        assertEquals(ReceiptWidth.MM_58.columns, page.lines.maxOf { it.text.length })
    }

    private companion object {
        const val ASCII_LAST = 126
    }
}
