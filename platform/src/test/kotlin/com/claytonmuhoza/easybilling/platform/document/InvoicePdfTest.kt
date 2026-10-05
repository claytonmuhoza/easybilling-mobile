package com.claytonmuhoza.easybilling.platform.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.domain.document.DocumentField
import com.claytonmuhoza.easybilling.domain.document.DocumentLine
import com.claytonmuhoza.easybilling.domain.document.DocumentLogo
import com.claytonmuhoza.easybilling.domain.document.DocumentNotice
import com.claytonmuhoza.easybilling.domain.document.InvoiceDocument
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The pages of the A4 PDF of an invoice (UC-G02), drawn as images, and the QR code read back. What
 * the document says is checked in the domain; here, how it is drawn. The PDF file itself needs a
 * device: see `InvoicePdfFileTest` in the instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class InvoicePdfTest {

    private val identifier = "4000000001/ws400000000100001/20261003101542/FN-00048/2026"

    private fun field(label: String, value: String, obrName: String? = null) = DocumentField(obrName, label, value)

    private fun line(designation: String, quantity: String, price: String, total: String) = DocumentLine(
        listOf(designation, quantity, price, "0", "0", total, "0", total, total).map { field("", it) },
    )

    private fun document(
        lines: List<DocumentLine> = listOf(
            line("Savon de Marseille 400 g", "10", "1 000", "10 000"),
            line("Sucre blanc cristallisé, sac de 50 kg, origine Moso, livré au magasin", "2,5", "118 000", "295 000"),
        ),
        notice: DocumentNotice? = null,
        logo: DocumentLogo? = null,
        footer: String = "",
        qrContent: String? = null,
        registered: Boolean = true,
    ) = InvoiceDocument(
        title = "FACTURE",
        number = field("Facture n°", "FN-00048/2026"),
        date = field("Date", "03/10/2026 10:15:42"),
        identifier = field("Identifiant", identifier),
        notice = notice,
        seller = listOf(
            field("Vendeur", "Boutique Umuco SPRL"),
            field("NIF", "4000000001"),
            field("Registre de commerce", "RC 12345"),
            field("Type de contribuable", "Personne morale"),
            field("Forme juridique", "SPRL"),
            field("Secteur d'activité", "Commerce général"),
            field("Centre fiscal", "DMC"),
            field("Téléphone", "+257 22 22 22 22"),
            field("Commune", "Mukaza"),
            field("Quartier", "Rohero"),
            field("Avenue", "Avenue de l'Industrie"),
            field("Assujetti à la TVA", "Oui"),
            field("Assujetti à la taxe de consommation", "Non"),
            field("Assujetti au prélèvement forfaitaire", "Non"),
        ),
        customer = listOf(
            field("Client", "Ets Kaneza"),
            field("NIF", "4000202020"),
            field("Adresse", "Bujumbura, Rohero"),
            field("Assujetti à la TVA", "Oui"),
        ),
        payment = listOf(field("Paiement", "Espèces"), field("Devise", "BIF")),
        columns = listOf("Désignation", "Qté", "P.U. HT", "TC", "PFL", "HTVA", "TVA", "TVAC", "Total"),
        lines = lines,
        totals = listOf(
            field("Total HTVA", "305 000 BIF"),
            field("TVA", "54 900 BIF"),
            field("Total à payer", "359 900 BIF"),
        ),
        obr = if (registered) {
            listOf(field("N° d'enregistrement OBR", "4530253"), field("Enregistrée le", "2026-10-03 10:15:44"))
        } else {
            emptyList()
        },
        footer = footer,
        logo = logo,
        qrContent = qrContent,
    )

    /** A logo made for the tests: a green square with a white disc. */
    private fun logo(): DocumentLogo {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(0, 106, 96))
            drawCircle(60f, 60f, 34f, android.graphics.Paint().apply { color = Color.WHITE })
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return DocumentLogo(png.toByteArray())
    }

    /** Draws [document] as the PDF does, on bitmaps of [scale] pixels per point. */
    private fun pages(document: InvoiceDocument, scale: Int = 2): List<Bitmap> {
        val pages = mutableListOf<Bitmap>()
        InvoicePainter(document) {
            val size = InvoicePage.WIDTH * scale to InvoicePage.HEIGHT * scale
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            pages += bitmap
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                scale(scale.toFloat(), scale.toFloat())
            }
        }.paint()
        return pages
    }

    private fun Bitmap.capture(name: String) = captureRoboImage("src/test/screenshots/$name.png")

    /** @return the text of the QR code found in this image, or null. */
    private fun Bitmap.qrText(): String? {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return runCatching { QRCodeReader().decode(image, mapOf(DecodeHintType.TRY_HARDER to true)).text }.getOrNull()
    }

    @Test
    fun `an invoice of two lines fits on one page`() {
        val pages = pages(document())

        assertEquals(1, pages.size)
        pages.single().capture("invoice_pdf")
    }

    @Test
    fun `the logo, the footer and the QR code are drawn, and the QR code reads as the identifier`() {
        val footer = "Merci de votre visite – Boutique Umuco"
        val customised = document(logo = logo(), footer = footer, qrContent = identifier)

        assertEquals(identifier, pages(customised, scale = 3).single().qrText())
        pages(customised).single().capture("invoice_pdf_customised")
    }

    @Test
    fun `without the setting there is no QR code`() {
        assertNull(pages(document(), scale = 3).single().qrText())
        assertEquals(identifier, pages(document(qrContent = identifier), scale = 3).single().qrText())
    }

    @Test
    fun `an invoice the OBR has not registered says so across the page`() {
        pages(document(notice = DocumentNotice.PENDING, registered = false)).single().capture("invoice_pdf_pending")
        pages(document(notice = DocumentNotice.REJECTED, registered = false)).single().capture("invoice_pdf_rejected")
    }

    @Test
    fun `a long invoice continues on other pages, each with the header of the table`() {
        val many = List(45) { line("Article numéro ${it + 1}", "1", "1 000", "1 000") }
        val long = document(lines = many, footer = "Merci de votre visite", qrContent = identifier)

        val pages = pages(long)

        assertEquals(2, pages.size)
        assertEquals(identifier, pages(long, scale = 3).last().qrText())
        pages.first().capture("invoice_pdf_long_page_1")
        pages.last().capture("invoice_pdf_long_page_2")
    }

    @Test
    fun `a logo is stored as a PNG no larger than its limit, and what is not an image is refused`() {
        val large = Bitmap.createBitmap(1_800, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpeg = ByteArrayOutputStream().also { large.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

        val png = assertNotNull(LogoImage.toPng(jpeg))

        val stored = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(LogoImage.MAX_SIDE to LogoImage.MAX_SIDE / 2, stored.width to stored.height)
        assertNull(LogoImage.toPng("not an image".toByteArray()))
    }
}
