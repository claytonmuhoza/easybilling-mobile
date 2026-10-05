package com.claytonmuhoza.easybilling.platform.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.claytonmuhoza.easybilling.domain.document.InvoiceDocument
import com.claytonmuhoza.easybilling.domain.document.InvoicePdfWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/** Writes an invoice as an A4 PDF with the PDF support of Android, without any other library. */
class AndroidInvoicePdfWriter : InvoicePdfWriter {

    override fun write(document: InvoiceDocument): ByteArray {
        val pdf = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            var number = 0
            InvoicePainter(document) {
                page?.let { pdf.finishPage(it) }
                val info = PdfDocument.PageInfo.Builder(InvoicePage.WIDTH, InvoicePage.HEIGHT, ++number).create()
                pdf.startPage(info).also { page = it }.canvas
            }.paint()
            page?.let { pdf.finishPage(it) }
            return ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
        } finally {
            pdf.close()
        }
    }
}

/**
 * A QR code: the squares that carry a text, here the identifier of an invoice.
 *
 * @property matrix the squares, as computed by ZXing.
 */
internal class QrCode private constructor(private val matrix: BitMatrix) {

    /**
     * Draws the code as a square of [size] at ([left], [top]), black on white.
     *
     * @param canvas where to draw.
     * @param left the left of the code.
     * @param top the top of the code.
     * @param size the side of the code.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, size: Float) {
        val white = Paint().apply { color = Color.WHITE }
        val black = Paint().apply { color = Color.BLACK }
        canvas.drawRect(left, top, left + size, top + size, white)
        val module = size / matrix.width
        for (row in 0 until matrix.height) {
            for (column in 0 until matrix.width) {
                if (matrix[column, row]) {
                    val x = left + column * module
                    val y = top + row * module
                    canvas.drawRect(x, y, x + module, y + module, black)
                }
            }
        }
    }

    /** Ways to compute a code. */
    companion object {
        /** White squares kept around the code, which a reader needs to find it. */
        private const val QUIET_ZONE = 2

        /**
         * @param content the text the code carries.
         * @return the code, with a medium error correction: it stays readable slightly damaged.
         */
        fun of(content: String): QrCode {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            return QrCode(QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints))
        }
    }
}
