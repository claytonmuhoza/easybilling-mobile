package com.claytonmuhoza.easybilling.platform.document

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.claytonmuhoza.easybilling.domain.document.DocumentField
import com.claytonmuhoza.easybilling.domain.document.InvoiceDocument

/** The size of an A4 page and what the painter leaves around its content, in points (1/72 inch). */
internal object InvoicePage {
    const val WIDTH = 595
    const val HEIGHT = 842
    const val MARGIN = 36f

    /** Room kept at the bottom of every page for the footer and the page number. */
    const val FOOTER_ROOM = 44f

    /** The left and the right of the content. */
    const val LEFT = MARGIN
    const val RIGHT = WIDTH - MARGIN

    /** Below this line a page holds only its footer. */
    const val BOTTOM = HEIGHT - MARGIN - FOOTER_ROOM
}

/** The sizes of the texts and the spaces between the blocks of an invoice, in points. */
private object Metrics {
    const val SIZE_SMALL = 7.5f
    const val SIZE_TEXT = 8.5f
    const val SIZE_STRONG = 11f
    const val SIZE_TITLE = 18f

    /** Space between two lines of text, between two fields, around a cell and between two blocks. */
    const val LEADING = 2f
    const val FIELD = 3f
    const val GAP = 5f
    const val SECTION = 14f

    const val LOGO_HEIGHT = 64f
    const val LOGO_WIDTH = 200f
    const val NUMBER_COLUMN = 47f
    const val TOTALS_WIDTH = 230f
    const val QR_SIZE = 84f
    const val RULE = 0.7f
    const val SHADE = 0xFFE8ECEB.toInt()
}

private const val QR_CAPTION = "Identifiant de la facture"

/** @return the field as one text: its name, then its value. */
private fun DocumentField.text() = "$label : $value"

private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

/**
 * @param text a text.
 * @param width the room for a line.
 * @param breakAnywhere true to cut between any two characters, for a text without spaces.
 * @return [text] cut in lines no wider than [width]; a word longer than a line is cut too.
 */
private fun Paint.wrap(text: String, width: Float, breakAnywhere: Boolean = false): List<String> {
    val lines = mutableListOf<String>()
    var current = ""
    val words = if (breakAnywhere) text.map { it.toString() } else text.split(' ').filter { it.isNotEmpty() }
    for (word in words) {
        val candidate = if (current.isEmpty() || breakAnywhere) current + word else "$current $word"
        if (measureText(candidate) <= width || current.isEmpty()) {
            current = candidate
        } else {
            lines += current
            current = word
        }
        while (measureText(current) > width && current.length > 1) {
            val fits = breakText(current, true, width, null).coerceAtLeast(1)
            lines += current.take(fits)
            current = current.drop(fits)
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

/**
 * The pages being drawn: the current one, how far down it is filled, and the footer of each.
 *
 * @param footer the text written at the bottom of every page; may be empty.
 * @param nextPage gives the canvas of a new page; the previous page is then finished.
 */
private class InvoicePages(private val footer: String, private val nextPage: () -> Canvas) {
    private val small = textPaint(Metrics.SIZE_SMALL, color = Color.DKGRAY)
    private val rule = Paint().apply {
        color = Color.GRAY
        strokeWidth = Metrics.RULE
    }

    /** The page being drawn. */
    lateinit var canvas: Canvas
        private set

    /** How many pages were started. */
    var count = 0
        private set

    /** Where the next block starts on the page. */
    var y = 0f

    /** Starts a new page, with its footer and its number. */
    fun start() {
        canvas = nextPage()
        count++
        y = InvoicePage.MARGIN
        val top = InvoicePage.BOTTOM + Metrics.GAP
        canvas.drawLine(InvoicePage.LEFT, top, InvoicePage.RIGHT, top, rule)
        var line = top
        for (text in small.wrap(footer, InvoicePage.RIGHT - InvoicePage.LEFT)) {
            line += small.textSize + Metrics.LEADING
            canvas.drawText(text, (InvoicePage.WIDTH - small.measureText(text)) / 2, line, small)
        }
        val page = "Page $count"
        val pageStart = InvoicePage.RIGHT - small.measureText(page)
        canvas.drawText(page, pageStart, InvoicePage.HEIGHT - InvoicePage.MARGIN, small)
    }

    /**
     * @param height the height of the block to draw next.
     * @return whether a new page was started because the block no longer fitted on this one.
     */
    fun need(height: Float): Boolean = (y + height > InvoicePage.BOTTOM).also { if (it) start() }
}

/**
 * Draws an invoice on A4 pages (UC-G02): the header with the logo, what is said of an invoice that
 * is not registered, the seller and the customer side by side, the identifier, the lines, the
 * totals, what the OBR acknowledged and the QR code. It draws on any canvas: the pages of a PDF,
 * or bitmaps in the tests.
 *
 * @param document what to draw.
 * @param nextPage gives the canvas of a new page; the previous page is then finished.
 */
internal class InvoicePainter(private val document: InvoiceDocument, nextPage: () -> Canvas) {

    private val regular = textPaint(Metrics.SIZE_TEXT)
    private val bold = textPaint(Metrics.SIZE_TEXT, bold = true)
    private val small = textPaint(Metrics.SIZE_SMALL, color = Color.DKGRAY)
    private val title = textPaint(Metrics.SIZE_TITLE, bold = true)
    private val strong = textPaint(Metrics.SIZE_STRONG, bold = true)
    private val mono = textPaint(Metrics.SIZE_TEXT).apply { typeface = Typeface.MONOSPACE }
    private val rule = Paint().apply {
        color = Color.GRAY
        strokeWidth = Metrics.RULE
    }
    private val shade = Paint().apply { color = Metrics.SHADE }

    private val left = InvoicePage.LEFT
    private val right = InvoicePage.RIGHT
    private val pages = InvoicePages(document.footer, nextPage)
    private val canvas get() = pages.canvas

    /**
     * Draws the whole document.
     *
     * @return how many pages were drawn.
     */
    fun paint(): Int {
        pages.start()
        header()
        notice()
        parties()
        identifier()
        lines()
        totals()
        acknowledgement()
        return pages.count
    }

    private fun header() {
        val top = pages.y
        val logo = document.logo?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
        var logoHeight = 0f
        if (logo != null) {
            val scale = minOf(Metrics.LOGO_HEIGHT / logo.height, Metrics.LOGO_WIDTH / logo.width)
            logoHeight = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(left, top, left + logo.width * scale, top + logoHeight), null)
        }
        var line = top + title.textSize
        canvas.drawText(document.title, right - title.measureText(document.title), line, title)
        for (text in listOf("${document.number.label} ${document.number.value}", document.date.text())) {
            line += regular.textSize + Metrics.GAP
            canvas.drawText(text, right - bold.measureText(text), line, bold)
        }
        pages.y = maxOf(line, top + logoHeight) + Metrics.SECTION
    }

    private fun notice() {
        val notice = document.notice ?: return
        val height = strong.textSize + 2 * Metrics.GAP
        canvas.drawRect(left, pages.y, right, pages.y + height, shade)
        val start = (InvoicePage.WIDTH - strong.measureText(notice.text)) / 2
        canvas.drawText(notice.text, start, pages.y + height - Metrics.GAP - Metrics.LEADING, strong)
        pages.y += height + Metrics.SECTION
    }

    /** The seller on the left, the customer and the payment on the right. */
    private fun parties() {
        val middle = InvoicePage.WIDTH / 2f
        val top = pages.y
        val sellerEnd = fields(document.seller, left, middle - Metrics.GAP, top)
        val customerEnd = fields(document.customer + document.payment, middle + Metrics.GAP, right, top)
        pages.y = maxOf(sellerEnd, customerEnd) + Metrics.SECTION
    }

    /** Draws [fields] one under the other from [top], the first in bold. @return where they end. */
    private fun fields(fields: List<DocumentField>, from: Float, to: Float, top: Float): Float {
        var line = top
        fields.forEachIndexed { index, field ->
            val paint = if (index == 0) bold else regular
            for (text in paint.wrap(field.text(), to - from)) {
                line += paint.textSize + Metrics.FIELD
                canvas.drawText(text, from, line, paint)
            }
        }
        return line
    }

    private fun identifier() {
        canvas.drawText(document.identifier.label, left, pages.y + small.textSize, small)
        pages.y += small.textSize + Metrics.FIELD
        for (text in mono.wrap(document.identifier.value, right - left, breakAnywhere = true)) {
            pages.y += mono.textSize + Metrics.LEADING
            canvas.drawText(text, left, pages.y, mono)
        }
        pages.y += Metrics.SECTION
    }

    private fun lines() {
        val widths = columnWidths()
        val lineHeight = regular.textSize + Metrics.LEADING
        tableHeader(widths)
        for (line in document.lines) {
            val designation = regular.wrap(line.cells.first().value, widths.first() - 2 * Metrics.FIELD)
            val height = designation.size * lineHeight + 2 * Metrics.FIELD
            if (pages.need(height)) tableHeader(widths)
            val baseline = pages.y + Metrics.FIELD + regular.textSize
            designation.forEachIndexed { index, part ->
                canvas.drawText(part, left + Metrics.FIELD, baseline + index * lineHeight, regular)
            }
            var x = left + widths.first()
            line.cells.drop(1).forEachIndexed { index, cell ->
                x += widths[index + 1]
                canvas.drawText(cell.value, x - Metrics.FIELD - regular.measureText(cell.value), baseline, regular)
            }
            pages.y += height
            canvas.drawLine(left, pages.y, right, pages.y, rule)
        }
        pages.y += Metrics.SECTION
    }

    /** The designation takes what the numbers leave. */
    private fun columnWidths(): List<Float> {
        val numbers = List(document.columns.size - 1) { Metrics.NUMBER_COLUMN }
        return listOf(right - left - numbers.sum()) + numbers
    }

    private fun tableHeader(widths: List<Float>) {
        val height = small.textSize + 2 * Metrics.FIELD
        canvas.drawRect(left, pages.y, right, pages.y + height, shade)
        var x = left
        document.columns.forEachIndexed { index, label ->
            val end = x + widths[index] - Metrics.FIELD - bold.measureText(label)
            val start = if (index == 0) x + Metrics.FIELD else end
            canvas.drawText(label, start, pages.y + Metrics.FIELD + small.textSize, bold)
            x += widths[index]
        }
        pages.y += height
    }

    private fun totals() {
        pages.need(document.totals.size * (strong.textSize + Metrics.GAP) + Metrics.SECTION)
        val labels = right - Metrics.TOTALS_WIDTH
        document.totals.forEachIndexed { index, field ->
            val paint = if (index == document.totals.lastIndex) strong else regular
            pages.y += paint.textSize + Metrics.GAP
            canvas.drawText(field.label, labels, pages.y, paint)
            canvas.drawText(field.value, right - paint.measureText(field.value), pages.y, paint)
        }
        pages.y += Metrics.SECTION
    }

    /** What the OBR acknowledged, and the QR code of the identifier next to it. */
    private fun acknowledgement() {
        val qr = document.qrContent?.let { QrCode.of(it) }
        val qrHeight = if (qr == null) 0f else Metrics.QR_SIZE + small.textSize + Metrics.GAP
        pages.need(maxOf(qrHeight, document.obr.size * (regular.textSize + Metrics.FIELD)))
        val top = pages.y
        var textLeft = left
        if (qr != null) {
            qr.draw(canvas, left, top, Metrics.QR_SIZE)
            canvas.drawText(QR_CAPTION, left, top + Metrics.QR_SIZE + small.textSize + Metrics.LEADING, small)
            textLeft = left + Metrics.QR_SIZE + Metrics.SECTION
        }
        pages.y = maxOf(fields(document.obr, textLeft, right, top), top + qrHeight)
    }
}
