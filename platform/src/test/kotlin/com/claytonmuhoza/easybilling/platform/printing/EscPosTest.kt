package com.claytonmuhoza.easybilling.platform.printing

import com.claytonmuhoza.easybilling.domain.document.Receipt
import com.claytonmuhoza.easybilling.domain.document.ReceiptLine
import com.claytonmuhoza.easybilling.domain.document.ReceiptStyle
import com.claytonmuhoza.easybilling.domain.document.ReceiptWidth
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bytes sent to a ticket printer (UC-G01), checked one by one. */
class EscPosTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    private fun receipt(vararg lines: ReceiptLine, accents: Boolean = false) =
        Receipt(lines.toList(), ReceiptWidth.MM_58, accents)

    private val reset = bytes(0x1B, 0x40)
    private val feedAndCut = bytes(0x1B, 0x64, 0x03, 0x1D, 0x56, 0x42, 0x00)

    @Test
    fun `a plain line is its characters and a line feed, between the reset and the cut`() {
        val encoded = EscPos.encode(receipt(ReceiptLine("AB 12")))

        assertContentEquals(reset + "AB 12\n".toByteArray(Charsets.US_ASCII) + feedAndCut, encoded)
    }

    @Test
    fun `the title is centred, bold and twice as high, and the next line goes back to normal`() {
        val encoded = EscPos.encode(
            receipt(
                ReceiptLine("FACTURE", ReceiptStyle.TITLE, centred = true),
                ReceiptLine("Client : A"),
            ),
        )

        val centre = bytes(0x1B, 0x61, 0x01)
        val boldTall = bytes(0x1B, 0x45, 0x01, 0x1D, 0x21, 0x01)
        val left = bytes(0x1B, 0x61, 0x00)
        val normal = bytes(0x1B, 0x45, 0x00, 0x1D, 0x21, 0x00)
        val expected = reset + centre + boldTall + "FACTURE\n".toByteArray() +
            left + normal + "Client : A\n".toByteArray() + feedAndCut
        assertContentEquals(expected, encoded)
    }

    @Test
    fun `a bold line is not taller, and two lines of the same style repeat no command`() {
        val encoded = EscPos.encode(
            receipt(
                ReceiptLine("Vendeur : A", ReceiptStyle.BOLD),
                ReceiptLine("Suite", ReceiptStyle.BOLD),
            ),
        )

        val bold = bytes(0x1B, 0x45, 0x01, 0x1D, 0x21, 0x00)
        assertContentEquals(reset + bold + "Vendeur : A\nSuite\n".toByteArray() + feedAndCut, encoded)
    }

    @Test
    fun `without the accents option only plain characters leave`() {
        val encoded = EscPos.encode(receipt(ReceiptLine("Espèces à payer")))

        assertContentEquals(reset + "Especes a payer\n".toByteArray() + feedAndCut, encoded)
        assertTrue(encoded.all { it >= 0 }, "a byte above 127 would print a wrong character")
    }

    @Test
    fun `with the accents option the code page 850 is chosen and the accents are its bytes`() {
        val encoded = EscPos.encode(receipt(ReceiptLine("é à ç"), accents = true))

        val codePage = bytes(0x1B, 0x74, 0x02)
        // é, à and ç in the code page 850.
        val text = bytes(0x82, 0x20, 0x85, 0x20, 0x87, 0x0A)
        assertContentEquals(reset + codePage + text + feedAndCut, encoded)
    }

    @Test
    fun `a line is never longer in bytes than in characters`() {
        val line = "Total à payer    2 360 BIF"

        val encoded = EscPos.encode(receipt(ReceiptLine(line), accents = true))

        val codePage = 3
        assertEquals(reset.size + codePage + line.length + 1 + feedAndCut.size, encoded.size)
    }
}
