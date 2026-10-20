package com.labelcropper.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal PDF writer whose pages contain only raster images (plus optional
 * dashed guide lines).
 *
 * Crops are rasterized rather than copied as vector content because some
 * label-printer drivers mis-render embedded fonts / Form XObjects and print
 * garbled text, while a plain image always prints correctly.
 *
 * Each page is written to the stream as soon as it's added, so memory use
 * stays flat no matter how many pages there are.
 */
class PdfImageWriter(output: OutputStream) : Closeable {

    class Placement(val image: ArgbImage, val rect: PtRect)

    /** A straight line in top-down point coordinates. */
    class DashedLine(
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val gray: Double, val width: Double, val dash: Double,
    )

    private val out = CountingOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val offsets = HashMap<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = 3 // 1 = catalog, 2 = page tree
    private var closed = false

    val pageCount: Int get() = pageIds.size

    init {
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    fun addPage(widthPt: Double, heightPt: Double, images: List<Placement>, lines: List<DashedLine> = emptyList()) {
        check(!closed) { "writer is closed" }

        val imageIds = images.map { writeImage(it.image) }

        val content = StringBuilder()
        images.forEachIndexed { i, p ->
            // PDF space is bottom-up; PtRect is top-down.
            val r = p.rect
            content.append("q ${n(r.width)} 0 0 ${n(r.height)} ${n(r.x0)} ${n(heightPt - r.y1)} cm /Im$i Do Q\n")
        }
        for (l in lines) {
            content.append("q ${n(l.gray)} G ${n(l.width)} w [${n(l.dash)} ${n(l.dash)}] 0 d ")
            content.append("${n(l.x0)} ${n(heightPt - l.y0)} m ${n(l.x1)} ${n(heightPt - l.y1)} l S Q\n")
        }
        val contentBytes = content.toString().toByteArray(Charsets.US_ASCII)
        val contentId = beginObject()
        out.writeAscii("<< /Length ${contentBytes.size} >>\nstream\n")
        out.write(contentBytes)
        out.writeAscii("\nendstream\nendobj\n")

        val xobjects = imageIds.withIndex().joinToString(" ") { (i, id) -> "/Im$i $id 0 R" }
        val pageId = beginObject()
        out.writeAscii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(widthPt)} ${n(heightPt)}] " +
                "/Resources << /XObject << $xobjects >> >> /Contents $contentId 0 R >>\nendobj\n",
        )
        pageIds += pageId
    }

    private fun writeImage(img: ArgbImage): Int {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(Deflater.DEFAULT_COMPRESSION), 64 * 1024).use { z ->
            val row = ByteArray(img.width * 3)
            for (y in 0 until img.height) {
                val base = y * img.width
                for (x in 0 until img.width) {
                    val p = img.pixels[base + x]
                    row[x * 3] = (p shr 16).toByte()
                    row[x * 3 + 1] = (p shr 8).toByte()
                    row[x * 3 + 2] = p.toByte()
                }
                z.write(row)
            }
        }
        val id = beginObject()
        out.writeAscii(
            "<< /Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${compressed.size()} >>\nstream\n",
        )
        compressed.writeTo(out)
        out.writeAscii("\nendstream\nendobj\n")
        return id
    }

    private fun beginObject(id: Int = nextId++): Int {
        offsets[id] = out.count
        out.writeAscii("$id 0 obj\n")
        return id
    }

    override fun close() {
        if (closed) return
        closed = true

        beginObject(2)
        out.writeAscii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        beginObject(1)
        out.writeAscii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val size = nextId
        val xref = out.count
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) {
            sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.getValue(id)))
        }
        sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.writeAscii(sb.toString())
        out.close()
    }

    private fun n(v: Double): String =
        String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
        override fun close() = target.close()

        fun writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    }
}
