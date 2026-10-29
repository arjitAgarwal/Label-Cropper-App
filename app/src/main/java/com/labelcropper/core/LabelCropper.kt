package com.labelcropper.core

import java.io.Closeable
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A multi-page document that can be rasterized one page at a time. */
interface PageSource : Closeable {
    val name: String
    val pageCount: Int

    /** Renders page [index] on white at [LabelCropper.RENDER_ZOOM] (points to pixels). */
    fun renderPage(index: Int): RenderedPage
}

interface RenderedPage : Closeable {
    val gray: GrayImage

    /**
     * Pixels of [rect] (in render pixels), produced at [width] x [height].
     * Implementations may re-render the region at that size rather than
     * resampling, for sharper output.
     */
    fun crop(rect: PixelRect, width: Int, height: Int): ArgbImage
}

object LabelCropper {
    const val RENDER_DPI = 300.0
    const val RENDER_ZOOM = RENDER_DPI / 72.0

    /** Crops are never stored above this resolution at their placed size on the output page. */
    const val OUTPUT_DPI = 300.0

    // Meesho labels: one per 5x3in landscape page.
    const val MEESHO_PAGE_W = 5.0 * 72
    const val MEESHO_PAGE_H = 3.0 * 72
    const val MEESHO_MARGIN = 6.0

    // Flipkart labels: ~3x5in portrait, matching the cropped label's own aspect ratio.
    const val FLIPKART_PAGE_W = 219.0
    const val FLIPKART_PAGE_H = 357.0

    // Invoices (both platforms): two per A4 portrait page, stacked.
    const val INVOICE_PAGE_W = 595.0
    const val INVOICE_PAGE_H = 842.0
    const val INVOICE_MARGIN = 18.0
    const val INVOICE_GAP = 10.0

    class MeeshoResult(val labelPages: Int, val invoicePages: Int, val skipped: List<String>)

    class FlipkartResult(
        val labelPages: Int,
        val invoicePages: Int,
        val invoicesFound: Int,
        val skippedLabels: List<String>,
        val skippedInvoices: List<String>,
    )

    /** Called after each source page with (pagesDone, pagesTotal). */
    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    /**
     * Writes each page's label to [labelsOut] and its Bill of Supply invoice
     * (merged two per A4 page) to [invoicesOut], in one pass.
     */
    fun cropMeesho(
        sources: List<PageSource>,
        labelsOut: OutputStream,
        invoicesOut: OutputStream,
        progress: Progress = Progress { _, _ -> },
    ): MeeshoResult {
        val m = MEESHO_MARGIN
        val labelZone = PtRect(m, m, MEESHO_PAGE_W - m, MEESHO_PAGE_H - m)

        val skipped = mutableListOf<String>()
        val total = sources.sumOf { it.pageCount }
        var done = 0
        val labels = PdfImageWriter(labelsOut)
        val invoices = InvoiceSheets(invoicesOut)
        labels.use {
            invoices.use {
                for (src in sources) {
                    for (i in 0 until src.pageCount) {
                        src.renderPage(i).use { page ->
                            val split = MeeshoLayout.findSplit(page.gray, RENDER_ZOOM)
                            if (split == null) {
                                skipped += "${src.name} page ${i + 1}"
                            } else {
                                labels.addPage(MEESHO_PAGE_W, MEESHO_PAGE_H, listOf(place(page, split.label, labelZone)))
                                invoices.add(page, split.invoice)
                            }
                        }
                        progress.update(++done, total)
                    }
                }
                invoices.finish()
            }
        }
        return MeeshoResult(labels.pageCount, invoices.pageCount, skipped)
    }

    /**
     * Writes the cropped labels to [labelsOut] and the Tax Invoices found
     * below them (merged two per A4 page) to [invoicesOut], in one pass.
     */
    fun cropFlipkart(
        sources: List<PageSource>,
        labelsOut: OutputStream,
        invoicesOut: OutputStream,
        progress: Progress = Progress { _, _ -> },
    ): FlipkartResult {
        val labelZone = PtRect(0.0, 0.0, FLIPKART_PAGE_W, FLIPKART_PAGE_H)

        val skippedLabels = mutableListOf<String>()
        val skippedInvoices = mutableListOf<String>()
        var invoicesFound = 0
        val total = sources.sumOf { it.pageCount }
        var done = 0

        val labels = PdfImageWriter(labelsOut)
        val invoices = InvoiceSheets(invoicesOut)
        labels.use {
            invoices.use {
                for (src in sources) {
                    for (i in 0 until src.pageCount) {
                        src.renderPage(i).use { page ->
                            val mask = page.gray.inkMask(FlipkartLayout.INK_THRESHOLD)

                            val labelBox = FlipkartLayout.labelBox(mask, RENDER_ZOOM)
                            if (labelBox == null) {
                                skippedLabels += "${src.name} page ${i + 1}"
                            } else {
                                labels.addPage(FLIPKART_PAGE_W, FLIPKART_PAGE_H, listOf(place(page, labelBox, labelZone)))
                            }

                            val invoiceBox = FlipkartLayout.invoiceBox(mask, RENDER_ZOOM)
                            if (invoiceBox == null) {
                                skippedInvoices += "${src.name} page ${i + 1}"
                            } else {
                                invoicesFound++
                                invoices.add(page, invoiceBox)
                            }
                        }
                        progress.update(++done, total)
                    }
                }
                invoices.finish()
            }
        }
        return FlipkartResult(labels.pageCount, invoices.pageCount, invoicesFound, skippedLabels, skippedInvoices)
    }

    /** Stacks invoice crops two per A4 page, with a dashed cut guide between each pair. */
    private class InvoiceSheets(out: OutputStream) : Closeable {
        private val writer = PdfImageWriter(out)
        private val halfH = INVOICE_PAGE_H / 2
        private val zones = listOf(
            PtRect(INVOICE_MARGIN, INVOICE_MARGIN, INVOICE_PAGE_W - INVOICE_MARGIN, halfH - INVOICE_GAP / 2),
            PtRect(INVOICE_MARGIN, halfH + INVOICE_GAP / 2, INVOICE_PAGE_W - INVOICE_MARGIN, INVOICE_PAGE_H - INVOICE_MARGIN),
        )
        private val cutGuide = PdfImageWriter.DashedLine(
            INVOICE_MARGIN, halfH, INVOICE_PAGE_W - INVOICE_MARGIN, halfH, gray = 0.6, width = 0.75, dash = 2.0,
        )
        private var pending: PdfImageWriter.Placement? = null

        val pageCount: Int get() = writer.pageCount

        fun add(page: RenderedPage, rect: PixelRect) {
            val first = pending
            if (first == null) {
                pending = place(page, rect, zones[0])
            } else {
                writer.addPage(INVOICE_PAGE_W, INVOICE_PAGE_H, listOf(first, place(page, rect, zones[1])), listOf(cutGuide))
                pending = null
            }
        }

        /** Writes an unpaired last invoice on a page of its own. */
        fun finish() {
            pending?.let { writer.addPage(INVOICE_PAGE_W, INVOICE_PAGE_H, listOf(it)) }
            pending = null
        }

        override fun close() = writer.close()
    }

    /** Fits [rect] of [page] into [zone] and crops it at no more than [OUTPUT_DPI] for its placed size. */
    private fun place(page: RenderedPage, rect: PixelRect, zone: PtRect): PdfImageWriter.Placement {
        val target = fitRect(rect.width.toDouble(), rect.height.toDouble(), zone)
        val maxW = ceil(target.width * OUTPUT_DPI / 72).toInt()
        val scale = minOf(1.0, maxW.toDouble() / rect.width)
        val w = max(1, (rect.width * scale).roundToInt())
        val h = max(1, (rect.height * scale).roundToInt())
        return PdfImageWriter.Placement(page.crop(rect, w, h), target)
    }
}
