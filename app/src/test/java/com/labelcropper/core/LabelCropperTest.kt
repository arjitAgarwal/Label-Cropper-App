package com.labelcropper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class LabelCropperTest {

    // --- Synthetic tests (always run) ----------------------------------------

    private fun blank(w: Int, h: Int) = ByteArray(w * h) { 255.toByte() }

    private fun hline(px: ByteArray, w: Int, y: Int, x0: Int, x1: Int, thickness: Int, gray: Int = 0, dash: Int = 0) {
        for (yy in y until y + thickness) for (x in x0 until x1) {
            if (dash == 0 || ((x - x0) / dash) % 2 == 0) px[yy * w + x] = gray.toByte()
        }
    }

    @Test
    fun meeshoSplitsBelowGreyBar() {
        val zoom = 2.0
        val w = 1190
        val h = 1300
        val px = blank(w, h)
        // Grey "Bill of Supply" bar with a rule on each edge, between Product Details and Bill To.
        hline(px, w, 704, 28, 1162, 42, gray = 240)
        for (y in listOf(26, 593, 700, 746, 1188)) hline(px, w, y, 24, 1166, 4)

        val split = assertNotNullAndGet(MeeshoLayout.findSplit(GrayImage(w, h, px), zoom))
        assertEquals(PixelRect(24, 28, 1166, 748), split.label)
        assertEquals(PixelRect(24, 748, 1166, 1190), split.invoice)
    }

    @Test
    fun meeshoRejectsPageWithTooFewRules() {
        val w = 600
        val h = 600
        val px = blank(w, h)
        for (y in listOf(20, 300, 500)) hline(px, w, y, 10, 590, 3)
        assertNull(MeeshoLayout.findSplit(GrayImage(w, h, px), 1.0))
    }

    @Test
    fun meeshoFallsBackToThirdRuleWithoutGreyBar() {
        val w = 600
        val h = 600
        val px = blank(w, h)
        for (y in listOf(20, 200, 300, 400, 500)) hline(px, w, y, 10, 590, 2)
        val split = assertNotNullAndGet(MeeshoLayout.findSplit(GrayImage(w, h, px), 1.0))
        assertEquals(301, split.label.bottom)
        assertEquals(501, split.invoice.bottom)
    }

    @Test
    fun flipkartSplitsAtDashedRule() {
        val zoom = 2.0
        val w = 1000
        val h = 1400
        val px = blank(w, h)
        for (y in 100 until 600 step 20) hline(px, w, y, 100, 700, 3)        // label text-ish rows
        hline(px, w, 700, 50, 950, 3, dash = 9)                              // dashed cut line
        for (y in 760 until 1200 step 20) hline(px, w, y, 60, 940, 3)        // invoice rows
        val mask = GrayImage(w, h, px).inkMask(FlipkartLayout.INK_THRESHOLD)

        assertEquals(700..702, FlipkartLayout.findDashedRuleSpan(mask, startRow = 20))
        assertEquals(PixelRect(100, 100, 699, 582), FlipkartLayout.labelBox(mask, zoom))
        assertEquals(PixelRect(54, 754, 945, 1188), FlipkartLayout.invoiceBox(mask, zoom))
    }

    @Test
    fun pdfWriterProducesWellFormedXref() {
        val out = ByteArrayOutputStream()
        PdfImageWriter(out).use { pdf ->
            val img = ArgbImage(2, 2, intArrayOf(-1, 0xFF000000.toInt(), 0xFF000000.toInt(), -1))
            pdf.addPage(100.0, 50.0, listOf(PdfImageWriter.Placement(img, PtRect(10.0, 10.0, 40.0, 40.0))))
            pdf.addPage(100.0, 50.0, emptyList(), listOf(PdfImageWriter.DashedLine(0.0, 25.0, 100.0, 25.0, 0.6, 0.75, 2.0)))
        }
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        val startxref = Regex("startxref\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref", startxref))
        // Every xref offset must point at "N 0 obj".
        val entries = text.substring(startxref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, e ->
            val off = e.substring(0, 10).toInt()
            assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj", off))
        }
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    // --- Real sample PDFs (run when -PfixturesDir is given) ------------------

    private val fixtures = System.getProperty("fixtures.dir").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
    private val outDir = System.getProperty("test.out.dir").orEmpty().takeIf { it.isNotBlank() }?.let(::File)

    /**
     * Pages pre-rendered at 300 DPI as p001.rgb, p002.rgb...: big-endian
     * int32 width and height, then packed RGB bytes. (Android unit tests
     * compile against android.jar, which has no java.awt / ImageIO.)
     */
    private class RgbSource(private val dir: File) : PageSource {
        private val files = dir.listFiles { f -> f.name.endsWith(".rgb") }!!.sortedBy { it.name }
        override val name: String = dir.name
        override val pageCount = files.size
        override fun renderPage(index: Int): RenderedPage = RgbPage(files[index].readBytes())
        override fun close() {}
    }

    private class RgbPage(data: ByteArray) : RenderedPage {
        private val w = DataInputStream(data.inputStream()).run { readInt() }
        private val h = DataInputStream(data.inputStream(4, 4)).run { readInt() }
        private val argb = IntArray(w * h) { i ->
            val o = 8 + i * 3
            (0xFF shl 24) or ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF)
        }

        override val gray = GrayImage(w, h, ByteArray(w * h) { GrayImage.grayOf(argb[it]).toByte() })

        /** Box-filter downscale of the cropped region. */
        override fun crop(rect: PixelRect, width: Int, height: Int): ArgbImage {
            val out = IntArray(width * height)
            for (oy in 0 until height) {
                val sy0 = rect.top + oy * rect.height / height
                val sy1 = maxOf(sy0 + 1, rect.top + (oy + 1) * rect.height / height)
                for (ox in 0 until width) {
                    val sx0 = rect.left + ox * rect.width / width
                    val sx1 = maxOf(sx0 + 1, rect.left + (ox + 1) * rect.width / width)
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                        val p = argb[sy * w + sx]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                    out[oy * width + ox] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            return ArgbImage(width, height, out)
        }

        override fun close() {}
    }

    private fun fixture(prefix: String): File? =
        fixtures?.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith(prefix) }

    @Test
    fun meeshoSampleMatchesPdfRules() {
        val dir = fixture("Sub_Order_Labels")
        assumeTrue(dir != null)
        val z = LabelCropper.RENDER_ZOOM
        // Vector y positions of rule 0, rule 2 and the last rule on each page, read from the PDF.
        val expected = listOf(
            Triple(13.0, 373.1, 594.3), Triple(13.0, 355.7, 576.9), Triple(13.0, 367.9, 589.2),
            Triple(13.0, 367.5, 588.7), Triple(13.0, 367.5, 588.7), Triple(13.0, 367.5, 588.7),
            Triple(13.0, 380.9, 590.1), Triple(13.0, 367.5, 588.7), Triple(13.0, 367.5, 588.7),
        )
        val report = StringBuilder()
        val src = RgbSource(dir!!)
        for (i in 0 until src.pageCount) {
            val gray = src.renderPage(i).gray
            val rules = MeeshoLayout.findRules(gray, z)
            val split = assertNotNullAndGet(MeeshoLayout.findSplit(gray, z))
            report.append("meesho p${i + 1} rules(pt)=${rules.map { "%.1f".format(it.centerY / z) }} label=${split.label} invoice=${split.invoice}\n")
            println(report.lines().dropLast(1).last())
            val (top, mid, bottom) = expected[i]
            assertEquals("p${i + 1} rule count", 7, rules.size)
            assertEquals("p${i + 1} top", top, split.label.top / z, 0.5)
            assertEquals("p${i + 1} split", mid, split.label.bottom / z, 0.5)
            assertEquals("p${i + 1} bottom", bottom, split.invoice.bottom / z, 0.5)
        }
        writeReport("meesho_report.txt", report)

        outDir?.let { out ->
            out.mkdirs()
            val r = File(out, "meesho.pdf").outputStream().use { LabelCropper.cropMeesho(listOf(src), it) }
            assertEquals(9, r.pageCount)
            assertTrue(r.skipped.isEmpty())
        }
    }

    @Test
    fun flipkartSampleBoxes() {
        val dir = fixture("invoice_labels")
        assumeTrue(dir != null)
        val z = LabelCropper.RENDER_ZOOM
        val report = StringBuilder()
        val src = RgbSource(dir!!)
        for (i in 0 until src.pageCount) {
            val mask = src.renderPage(i).gray.inkMask(FlipkartLayout.INK_THRESHOLD)
            val label = assertNotNullAndGet(FlipkartLayout.labelBox(mask, z))
            val invoice = assertNotNullAndGet(FlipkartLayout.invoiceBox(mask, z))
            report.append("flipkart p${i + 1} label=${label.left},${label.top},${label.right},${label.bottom} invoice=${invoice.left},${invoice.top},${invoice.right},${invoice.bottom}\n")
        }
        writeReport("flipkart_report.txt", report)

        outDir?.let { out ->
            out.mkdirs()
            val r = File(out, "flipkart_labels.pdf").outputStream().use { l ->
                File(out, "flipkart_invoices.pdf").outputStream().use { inv -> LabelCropper.cropFlipkart(listOf(src), l, inv) }
            }
            assertEquals(9, r.labelPages)
            assertEquals(9, r.invoicesFound)
            assertEquals(5, r.invoicePages)
        }
    }

    private fun writeReport(name: String, sb: StringBuilder) {
        print(sb)
        outDir?.let { it.mkdirs(); File(it, name).writeText(sb.toString()) }
    }
}
