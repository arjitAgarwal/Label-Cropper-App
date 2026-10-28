package com.labelcropper.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalCommandsTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 10x2: row 0 has pixels 0 and 9 black, row 1 is white. */
    private fun sample() = MonoImage.fromArgb(10, 2, IntArray(20) { if (it == 0 || it == 9) black else white })

    @Test
    fun packsBlackPixelsMsbFirst() {
        val img = sample()
        assertEquals(2, img.rowBytes)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x40, 0, 0), img.bits)
    }

    @Test
    fun tsplInvertsBitsAndKeepsPaddingWhite() {
        val out = ThermalCommands.label(PrinterLanguage.TSPL, sample(), 4, 6)
        val header = "CLS\r\nBITMAP 4,6,2,2,0,".toByteArray()
        assertArrayEquals(header, out.copyOfRange(0, header.size))
        val data = out.copyOfRange(header.size, header.size + 4)
        // 0 bits print: pixel 0 and 9 cleared, the 6 padding bits of each row's last byte set.
        assertArrayEquals(byteArrayOf(0x7F, 0xBF.toByte(), 0xFF.toByte(), 0xFF.toByte()), data)
        assertTrue(String(out, Charsets.US_ASCII).endsWith("\r\nPRINT 1,1\r\n"))
    }

    @Test
    fun escPosPadsLeftMarginAndFeedsToNextLabel() {
        val out = ThermalCommands.label(PrinterLanguage.ESC_POS, sample(), 16, 0)
        // GS v 0, 4 bytes per row (2 margin + 2 image), 2 rows.
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0, 4, 0, 2, 0), out.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x40, 0, 0, 0, 0), out.copyOfRange(8, 16))
        assertArrayEquals(byteArrayOf(0x1D, 0x0C), out.copyOfRange(16, 18))
        assertEquals(18, out.size)
    }
}
