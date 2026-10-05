package com.claytonmuhoza.easybilling.domain.document

import java.text.Normalizer

/**
 * The widths of paper a ticket is printed on (UC-A09).
 *
 * @property columns how many characters fit on a line, in the normal size.
 */
enum class ReceiptWidth(val columns: Int) {
    /** Paper of 58 mm. */
    MM_58(32),

    /** Paper of 80 mm. */
    MM_80(48),
}

/** How a line of a ticket is printed. */
enum class ReceiptStyle {
    /** The normal size. */
    NORMAL,

    /** In bold. */
    BOLD,

    /** In bold and twice as high: the title of the ticket. */
    TITLE,

    /** In bold and twice as high: the amount to pay. */
    TOTAL,
}

/**
 * A line of a ticket.
 *
 * @property text the characters of the line, no more than the paper holds.
 * @property style how it is printed.
 * @property centred whether it is centred instead of starting at the left.
 */
data class ReceiptLine(val text: String, val style: ReceiptStyle = ReceiptStyle.NORMAL, val centred: Boolean = false)

/**
 * A ticket (UC-G01): lines of text ready for a printer, which only adds its own commands. A ticket
 * carries the same mentions as the PDF; it has neither logo nor QR code.
 *
 * @property lines the lines, from top to bottom.
 * @property width the paper it is laid out for.
 * @property accents whether the text keeps its accents; without them every character is plain
 * ASCII, which any printer prints.
 */
data class Receipt(val lines: List<ReceiptLine>, val width: ReceiptWidth, val accents: Boolean) {

    /** @return this ticket with every character turned into plain ASCII, for a printer without accents. */
    fun withoutAccents(): Receipt = copy(lines = lines.map { it.copy(text = PlainText.of(it.text)) }, accents = false)

    /** Ways to lay a ticket out. */
    companion object {
        /**
         * @param document the invoice.
         * @param width the paper.
         * @param accents whether the printer prints accented characters.
         * @return the ticket of the invoice.
         */
        fun of(document: InvoiceDocument, width: ReceiptWidth, accents: Boolean): Receipt =
            Receipt(ReceiptLayout(width.columns).apply { invoice(document) }.lines, width, accents).plainUnless(accents)

        /**
         * @param printerName the name of the printer the page is sent to.
         * @param width the paper.
         * @param accents whether the printer prints accented characters.
         * @return a page that shows whether the width and the accents are set right.
         */
        fun testPage(printerName: String, width: ReceiptWidth, accents: Boolean): Receipt {
            val layout = ReceiptLayout(width.columns).apply {
                centred("PAGE DE TEST", ReceiptStyle.TITLE)
                centred(printerName)
                rule()
                // A ruler as wide as the paper: it wraps when the width is set too large.
                line(RULER.repeat(width.columns / RULER.length + 1).take(width.columns))
                text("Largeur : ${width.columns} caractères par ligne")
                text("Accents : é è à ç ù ô î")
                pair("Total à payer", "1 180 BIF", ReceiptStyle.TOTAL)
                rule()
            }
            return Receipt(layout.lines, width, accents).plainUnless(accents)
        }

        private const val RULER = "1234567890"
    }
}

/** @return this ticket as it is, or with every character turned into plain ASCII. */
private fun Receipt.plainUnless(accents: Boolean): Receipt = if (accents) this else withoutAccents()

/** Turns a text into characters any printer has. */
internal object PlainText {
    private val MARKS = Regex("\\p{Mn}+")
    private val REPLACED = mapOf(
        '’' to "'", '‘' to "'", '«' to "\"", '»' to "\"", '–' to "-", '—' to "-", '°' to ".", '×' to "x",
        'œ' to "oe", 'Œ' to "OE", ' ' to " ", ' ' to " ",
    )

    /** Last character of plain ASCII. */
    private const val ASCII_LAST = 126

    /**
     * @param text a text.
     * @return the text without accents; what has no plain form becomes `?`.
     */
    fun of(text: String): String {
        val replaced = text.map { REPLACED[it] ?: it.toString() }.joinToString("")
        return MARKS.replace(Normalizer.normalize(replaced, Normalizer.Form.NFD), "")
            .map { if (it.code in ' '.code..ASCII_LAST) it else '?' }
            .joinToString("")
    }
}

/**
 * Lays text out in lines of [columns] characters.
 *
 * @param columns how many characters fit on a line.
 */
private class ReceiptLayout(private val columns: Int) {
    val lines = mutableListOf<ReceiptLine>()

    /** A line as it is; it must fit. */
    fun line(text: String, style: ReceiptStyle = ReceiptStyle.NORMAL) {
        lines += ReceiptLine(text.take(columns), style)
    }

    /** A text from the left, on as many lines as it takes; the next ones start after [indent]. */
    fun text(text: String, style: ReceiptStyle = ReceiptStyle.NORMAL, indent: Int = 0) {
        wrap(text, columns - indent).forEachIndexed { index, part ->
            line(if (index == 0 && indent == 0) part else " ".repeat(indent) + part, style)
        }
    }

    /** A centred text, on as many lines as it takes. */
    fun centred(text: String, style: ReceiptStyle = ReceiptStyle.NORMAL) {
        wrap(text, columns).forEach { lines += ReceiptLine(it, style, centred = true) }
    }

    /** A line of dashes across the paper. */
    fun rule() = line("-".repeat(columns))

    /** [left] at the left and [right] at the right of the same line, or on two lines when too long. */
    fun pair(left: String, right: String, style: ReceiptStyle = ReceiptStyle.NORMAL) {
        // A line twice as high is as wide as the others: only its characters are taller.
        val room = if (style == ReceiptStyle.TOTAL) columns - TOTAL_MARGIN else columns
        if (left.length + right.length < room) {
            line(left + " ".repeat(room - left.length - right.length) + right, style)
        } else {
            text(left, style)
            line(" ".repeat((columns - right.length).coerceAtLeast(0)) + right, style)
        }
    }

    /** A text without spaces, cut wherever the paper ends. */
    fun unbroken(text: String) = text.chunked(columns).forEach { line(it) }

    fun invoice(document: InvoiceDocument) {
        centred(document.title, ReceiptStyle.TITLE)
        centred("${document.number.label} ${document.number.value}")
        centred(document.date.value)
        document.notice?.let { centred(it.text, ReceiptStyle.BOLD) }
        rule()
        fields(document.seller)
        rule()
        fields(document.customer + document.payment)
        rule()
        document.lines.forEach { item(it) }
        rule()
        document.totals.forEachIndexed { index, total ->
            val style = if (index == document.totals.lastIndex) ReceiptStyle.TOTAL else ReceiptStyle.NORMAL
            pair(total.label, total.value, style)
        }
        rule()
        text("${document.identifier.label} :")
        unbroken(document.identifier.value)
        document.obr.forEach { text("${it.label} : ${it.value}") }
        if (document.footer.isNotEmpty()) centred(document.footer)
    }

    /** The first field in bold, each one as its name and its value. */
    private fun fields(fields: List<DocumentField>) = fields.forEachIndexed { index, field ->
        text("${field.label} : ${field.value}", if (index == 0) ReceiptStyle.BOLD else ReceiptStyle.NORMAL)
    }

    /** A line of the invoice: its designation, quantity times unit price and total, then its taxes. */
    private fun item(line: DocumentLine) {
        val cells = line.cells
        text(cells[DESIGNATION].value)
        pair("${cells[QUANTITY].value} x ${cells[UNIT_PRICE].value}", cells.last().value)
        val taxes = cells.subList(UNIT_PRICE + 1, cells.lastIndex)
            .joinToString(TAX_SEPARATOR) { "${it.label} ${it.value}" }
        wrap(taxes, columns - TAX_INDENT, TAX_SEPARATOR).forEach { line(" ".repeat(TAX_INDENT) + it) }
    }

    /** @return [text] cut at [separator] in parts no longer than [width]; a longer word is cut too. */
    private fun wrap(text: String, width: Int, separator: String = " "): List<String> {
        val parts = mutableListOf<String>()
        var current = ""
        for (word in text.split(separator).filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else current + separator + word
            if (candidate.length <= width) {
                current = candidate
            } else {
                if (current.isNotEmpty()) parts += current
                parts += word.chunked(width).dropLast(1)
                current = word.chunked(width).last()
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    private companion object {
        /** Where the designation, the quantity and the unit price are in the cells of a line. */
        const val DESIGNATION = 0
        const val QUANTITY = 1
        const val UNIT_PRICE = 2

        const val TAX_INDENT = 2
        const val TAX_SEPARATOR = "  "

        /** Columns left empty at the end of the amount to pay, so the taller line never wraps. */
        const val TOTAL_MARGIN = 6
    }
}
