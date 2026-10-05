package com.claytonmuhoza.easybilling.platform.printing

import com.claytonmuhoza.easybilling.domain.document.Receipt
import com.claytonmuhoza.easybilling.domain.document.ReceiptLine
import com.claytonmuhoza.easybilling.domain.document.ReceiptStyle
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Writes a ticket as ESC/POS commands, the language of the thermal ticket printers. Only what
 * every such printer knows is used: reset, alignment, bold, double height, feed and cut.
 */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LINE_FEED = 0x0A

    /** `ESC @`: back to the settings the printer starts with. */
    private val RESET = intArrayOf(ESC, '@'.code)

    /** `ESC t 2`: the code page PC850, the Western European one of every ESC/POS printer. */
    private val CODE_PAGE_850 = intArrayOf(ESC, 't'.code, 2)
    private const val CODE_PAGE_850_NAME = "IBM850"

    /** `ESC d 3`: three empty lines, so the last line clears the cutter. */
    private val FEED = intArrayOf(ESC, 'd'.code, 3)

    /** `GS V 66 0`: partial cut; a printer without a cutter ignores it. */
    private val CUT = intArrayOf(GS, 'V'.code, 66, 0)

    private const val DOUBLE_HEIGHT = 0x01

    /**
     * @param receipt the ticket.
     * @return the bytes to send to the printer.
     */
    fun encode(receipt: Receipt): ByteArray {
        // Accents need a code page the device can write; without it the ticket is printed plain.
        val accents = receipt.accents && Charset.isSupported(CODE_PAGE_850_NAME)
        val printable = if (accents) receipt else receipt.withoutAccents()
        val charset = if (accents) Charset.forName(CODE_PAGE_850_NAME) else Charsets.US_ASCII
        val out = ByteArrayOutputStream()
        out.command(RESET)
        if (accents) out.command(CODE_PAGE_850)
        var current = ReceiptLine("")
        for (line in printable.lines) {
            if (line.centred != current.centred) out.command(intArrayOf(ESC, 'a'.code, if (line.centred) 1 else 0))
            if (line.style != current.style) {
                out.command(intArrayOf(ESC, 'E'.code, if (line.style == ReceiptStyle.NORMAL) 0 else 1))
                out.command(intArrayOf(GS, '!'.code, if (line.style.tall) DOUBLE_HEIGHT else 0))
            }
            out.write(line.text.toByteArray(charset))
            out.write(LINE_FEED)
            current = line
        }
        out.command(FEED)
        out.command(CUT)
        return out.toByteArray()
    }

    private val ReceiptStyle.tall get() = this == ReceiptStyle.TITLE || this == ReceiptStyle.TOTAL

    private fun ByteArrayOutputStream.command(bytes: IntArray) = bytes.forEach { write(it) }
}
