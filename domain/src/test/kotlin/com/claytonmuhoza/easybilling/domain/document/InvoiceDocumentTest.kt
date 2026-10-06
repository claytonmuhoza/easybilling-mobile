package com.claytonmuhoza.easybilling.domain.document

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.customer.Customer
import com.claytonmuhoza.easybilling.domain.customer.VerifiedTin
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.testing.FakeAccessControl
import com.claytonmuhoza.easybilling.domain.testing.InMemoryDocumentSettingsRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryReceiptArchive
import com.claytonmuhoza.easybilling.domain.testing.SaleFixture
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an invoice shows once it leaves the application, as a PDF or a ticket (UC-G01, G02, A08):
 * every mandatory field of `addInvoice`, the identifier, what the OBR acknowledged.
 */
class InvoiceDocumentTest {

    private val fixture = SaleFixture()
    private val receipts = InMemoryReceiptArchive()
    private val settings = InMemoryDocumentSettingsRepository()
    private val build = BuildInvoiceDocument(fixture.invoices, fixture.profiles, fixture.users, receipts, settings)
    private val written = mutableListOf<InvoiceDocument>()
    private val access = FakeAccessControl()
    private val createPdf = CreateInvoicePdf(
        access,
        build,
        InvoicePdfWriter { document -> "%PDF of ${document.number.value}".toByteArray().also { written += document } },
        fixture.audit,
    )
    private val save = SaveDocumentSettings(settings)

    /** Issues an invoice of two soaps at 1 000 without taxes, to [customer] or at the counter. */
    private suspend fun issued(customer: Customer? = null, payment: PaymentType = PaymentType.CASH): Invoice {
        if (fixture.profiles.current() == null) fixture.ready()
        val soap = fixture.items.findByCode("ART-0001") ?: fixture.item("ART-0001", "1000")
        fixture.editSale.addItem(soap.id)
        fixture.editSale.addItem(soap.id)
        fixture.editSale.setCustomer(customer?.id)
        val draft = fixture.editSale.setPaymentType(payment)
        return assertIs<IssueSale.Result.Issued>(fixture.issueSale(draft.id, fixture.alice)).invoice
    }

    private suspend fun documentOf(invoice: Invoice): InvoiceDocument =
        assertIs<BuildInvoiceDocument.Result.Built>(build(invoice.id)).document

    private fun InvoiceDocument.value(obrName: String): String? = fields().firstOrNull { it.obrName == obrName }?.value

    @Test
    fun `every mandatory field of addInvoice is on the document, with the identifier in clear`() = runTest {
        val document = documentOf(issued())

        val shown = document.fields().mapNotNull { it.obrName }.toSet()
        assertEquals(emptySet(), InvoiceDocument.MANDATORY_FIELDS - shown)
        assertTrue(document.fields().none { it.obrName in InvoiceDocument.MANDATORY_FIELDS && it.value.isBlank() })
        for (line in document.lines) {
            val cells = line.cells.mapNotNull { it.obrName }.toSet()
            assertEquals(emptySet(), InvoiceDocument.MANDATORY_ITEM_FIELDS - cells)
        }
        assertEquals(document.columns.size, document.lines.single().cells.size)
    }

    @Test
    fun `the document says who sells, to whom, what and for how much, in French`() = runTest {
        val invoice = issued(payment = PaymentType.CREDIT)

        val document = documentOf(invoice)

        assertEquals("FACTURE", document.title)
        assertEquals("FN-00001/2026", document.number.value)
        // 08:00 UTC is 10:00 in Bujumbura.
        assertEquals("03/10/2026 10:00:00", document.value("invoice_date"))
        assertEquals(invoice.content.identifier, document.identifier.value)
        assertEquals("Boutique Umuco SPRL", document.value("tp_name"))
        assertEquals("4000000001", document.value("tp_TIN"))
        assertEquals("Personne morale", document.value("tp_type"))
        assertEquals("Oui", document.value("vat_taxpayer"))
        assertEquals("Non", document.value("ct_taxpayer"))
        assertEquals("DMC", document.value("tp_fiscal_center"))
        assertEquals("Crédit", document.value("payment_type"))
        assertEquals("Client comptoir", document.value("customer_name"))
        assertNull(document.value("customer_TIN"))

        val cells = document.lines.single().cells.associate { it.obrName to it.value }
        assertEquals("Article ART-0001", cells["item_designation"])
        assertEquals("2", cells["item_quantity"])
        assertEquals("1 000", cells["item_price"])
        assertEquals("2 000", cells["item_price_nvat"])
        assertEquals("360", cells["vat"])
        assertEquals("2 360", cells["item_total_amount"])
        assertEquals("2 360 BIF", document.totals.last().value)
    }

    @Test
    fun `the document names the cashier who issued the invoice (UC-H03)`() = runTest {
        val document = documentOf(issued())

        assertEquals(DocumentField(null, "Caissier", "Alice"), document.payment.last())
    }

    @Test
    fun `a customer with a TIN shows it, its address and whether it is registered for the VAT`() = runTest {
        fixture.ready()
        val details = Customer.counterDetails("Ets Kaneza").copy(
            tin = VerifiedTin("4000202020", "ETS KANEZA", fixture.time.now()),
            address = "Bujumbura, Rohero",
            vatPayer = true,
        )

        val document = documentOf(issued(fixture.customers.create(details)))

        assertEquals("Ets Kaneza", document.value("customer_name"))
        assertEquals("4000202020", document.value("customer_TIN"))
        assertEquals("Bujumbura, Rohero", document.value("customer_address"))
        assertEquals("Oui", document.value("vat_customer_payer"))
    }

    @Test
    fun `what the OBR acknowledged is shown once it exists`() = runTest {
        val invoice = issued()
        assertEquals(emptyList(), documentOf(invoice).obr)

        val receipt = ObrReceipt("4530253", "2026-10-03 10:04:01", "signature", "{}")
        receipts.archive(invoice.id, receipt, fixture.time.now())
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())

        val document = documentOf(invoice)
        assertEquals(listOf("4530253", "2026-10-03 10:04:01"), document.obr.map { it.value })
        assertNull(document.notice)
    }

    @Test
    fun `an invoice that is not registered says where it stands`() = runTest {
        val invoice = issued()
        assertEquals(DocumentNotice.PENDING, documentOf(invoice).notice)

        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Rejected("Le NIF est invalide."), fixture.time.now())
        assertEquals(DocumentNotice.REJECTED, documentOf(invoice).notice)

        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Pending, fixture.time.now())
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Registered, fixture.time.now())
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Cancelled, fixture.time.now())
        assertEquals(DocumentNotice.CANCELLED, documentOf(invoice).notice)
    }

    @Test
    fun `the footer, the logo and the QR code follow the settings`() = runTest {
        val invoice = issued()
        val plain = documentOf(invoice)
        assertEquals("", plain.footer)
        assertNull(plain.logo)
        assertNull(plain.qrContent)

        val logo = DocumentLogo(byteArrayOf(1, 2, 3))
        settings.write(DocumentSettings(footer = "Merci de votre visite", logo = logo, showQrCode = true))

        val customised = documentOf(invoice)
        assertEquals("Merci de votre visite", customised.footer)
        assertEquals(logo, customised.logo)
        // The QR code carries the identifier, so anyone can check the invoice at the OBR.
        assertEquals(invoice.content.identifier, customised.qrContent)
    }

    @Test
    fun `a resent invoice shows the seller it was sent again with`() = runTest {
        val invoice = issued()
        fixture.invoices.setStatus(invoice.id, InvoiceStatus.Rejected("refused"), fixture.time.now())
        fixture.saveProfile(TaxRegistrations(vat = true))
        fixture.invoices.replaceSeller(invoice.id, profileVersion = 2, identifier = invoice.content.identifier)

        assertEquals("Boutique Umuco SPRL", documentOf(invoice).value("tp_name"))
        assertEquals(BuildInvoiceDocument.Result.NotFound, build(InvoiceId(99)))
    }

    @Test
    fun `creating the PDF writes the document, names the file after the number and is audited`() = runTest {
        val invoice = issued()
        fixture.audit.events.clear()

        val created = assertIs<CreateInvoicePdf.Result.Created>(createPdf(invoice.id, fixture.alice))

        assertEquals("Facture-FN-00001-2026.pdf", created.fileName)
        assertEquals("%PDF of FN-00001/2026", created.bytes.decodeToString())
        assertEquals(invoice.content.identifier, written.single().identifier.value)
        assertEquals(AuditEvent.InvoicePdfCreated(fixture.alice, invoice.id), fixture.audit.events.single())
        assertEquals("INVOICE_PDF_CREATED", fixture.audit.events.single().action)

        // A second copy is the same document, without any mark; it is audited too.
        createPdf(invoice.id, fixture.alice)
        assertEquals(written[0], written[1])
        assertEquals(2, fixture.audit.events.size)
        assertEquals(CreateInvoicePdf.Result.NotFound, createPdf(InvoiceId(99), fixture.alice))
    }

    @Test
    fun `the footer is trimmed and limited, and the settings are kept`() = runTest {
        assertEquals(SaveDocumentSettings.Result.Saved, save(DocumentSettings(footer = "  Merci  ", showQrCode = true)))
        assertEquals(DocumentSettings(footer = "Merci", showQrCode = true), settings.read())

        val tooLong = "x".repeat(DocumentSettings.MAX_FOOTER_LENGTH + 1)
        assertEquals(SaveDocumentSettings.Result.FooterTooLong, save(DocumentSettings(footer = tooLong)))
        assertEquals("Merci", settings.read().footer)
    }

    @Test
    fun `a user who may not sell creates no PDF`() = runTest {
        val invoice = issued()
        val events = fixture.audit.events.size
        access.denied += Permission.SELL

        assertFailsWith<PermissionDeniedException> { createPdf(invoice.id, fixture.alice) }

        assertEquals(listOf(fixture.alice to Permission.SELL), access.required)
        assertEquals(events, fixture.audit.events.size)
    }
}
