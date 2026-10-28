package com.labelcropper.core

import java.io.ByteArrayOutputStream

/**
 * 1-bit image, rows packed MSB-first, [rowBytes] bytes per row. A set bit is black (printed).
 */
class MonoImage(val width: Int, val height: Int, val bits: ByteArray) {
    val rowBytes: Int get() = (width + 7) / 8

    init {
        require(bits.size == rowBytes * height) { "bits size ${bits.size} != ${rowBytes * height}" }
    }

    companion object {
        /** Thresholds packed ARGB pixels: anything darker than [threshold] (0-255) prints. */
        fun fromArgb(width: Int, height: Int, pixels: IntArray, threshold: Int = 160): MonoImage {
            val rowBytes = (width + 7) / 8
            val bits = ByteArray(rowBytes * height)
            for (y in 0 until height) {
                val base = y * width
                val out = y * rowBytes
                for (x in 0 until width) {
                    if (GrayImage.grayOf(pixels[base + x]) < threshold) {
                        val i = out + (x shr 3)
                        bits[i] = (bits[i].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
            return MonoImage(width, height, bits)
        }
    }
}

/** Command languages spoken by common Bluetooth thermal label printers. */
enum class PrinterLanguage {
    /** TSC / TSPL: label printers with gap sensing (most 4in shipping-label printers). */
    TSPL,

    /** ESC/POS raster: receipt-style printers. */
    ESC_POS,
}

object ThermalCommands {

    /** Printers in this class are 203 DPI: 8 dots per mm. */
    const val DOTS_PER_MM = 8

    /** Setup sent once per job, before the first label. */
    fun jobStart(language: PrinterLanguage, labelWidthMm: Int, labelHeightMm: Int, gapMm: Int = 2): ByteArray =
        when (language) {
            PrinterLanguage.TSPL -> ascii(
                "SIZE $labelWidthMm mm,$labelHeightMm mm\r\n" +
                    "GAP $gapMm mm,0 mm\r\n" +
                    "REFERENCE 0,0\r\n",
            )
            PrinterLanguage.ESC_POS -> byteArrayOf(0x1B, 0x40) // ESC @: initialise
        }

    /** One label holding [image] with its top-left corner at ([x], [y]) dots. */
    fun label(language: PrinterLanguage, image: MonoImage, x: Int, y: Int): ByteArray = when (language) {
        PrinterLanguage.TSPL -> tsplLabel(image, x, y)
        PrinterLanguage.ESC_POS -> escPosLabel(image, x, y)
    }

    private fun tsplLabel(image: MonoImage, x: Int, y: Int): ByteArray {
        val out = ByteArrayOutputStream(image.bits.size + 64)
        out.write(ascii("CLS\r\nBITMAP $x,$y,${image.rowBytes},${image.height},0,"))
        // TSPL's BITMAP prints the 0 bits, so the data is inverted.
        val inverted = ByteArray(image.bits.size) { (image.bits[it].toInt() xor 0xFF).toByte() }
        // Padding bits past the image width in the last byte of each row must stay white.
        val pad = image.rowBytes * 8 - image.width
        if (pad > 0) {
            val mask = (1 shl pad) - 1
            for (row in 0 until image.height) {
                val i = row * image.rowBytes + image.rowBytes - 1
                inverted[i] = (inverted[i].toInt() or mask).toByte()
            }
        }
        out.write(inverted)
        out.write(ascii("\r\nPRINT 1,1\r\n"))
        return out.toByteArray()
    }

    private fun escPosLabel(image: MonoImage, x: Int, y: Int): ByteArray {
        // ESC/POS has no positioning for raster images: pad the left margin into each row.
        val shiftBytes = x / 8
        val rowBytes = shiftBytes + image.rowBytes
        val out = ByteArrayOutputStream(rowBytes * (image.height + y) + 64)
        if (y > 0) {
            var feed = y
            while (feed > 0) {
                val n = minOf(feed, 255)
                out.write(byteArrayOf(0x1B, 0x4A, n.toByte())) // ESC J n: feed n dots
                feed -= n
            }
        }
        // GS v 0 in bands; some printers cap a single image's height.
        var row = 0
        while (row < image.height) {
            val band = minOf(BAND_ROWS, image.height - row)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
            out.write(byteArrayOf((rowBytes and 0xFF).toByte(), (rowBytes shr 8).toByte()))
            out.write(byteArrayOf((band and 0xFF).toByte(), (band shr 8).toByte()))
            val blank = ByteArray(shiftBytes)
            for (r in row until row + band) {
                out.write(blank)
                out.write(image.bits, r * image.rowBytes, image.rowBytes)
            }
            row += band
        }
        out.write(byteArrayOf(0x1D, 0x0C)) // GS FF: feed to the next label (ignored by receipt printers)
        return out.toByteArray()
    }

    private const val BAND_ROWS = 256

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
}
