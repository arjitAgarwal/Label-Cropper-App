package com.labelcropper.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Flipkart (E-Kart-style) page analysis. A direct port of the desktop app's
 * find_dashed_rule_span / find_content_bbox / find_invoice_bbox, operating on
 * an ink mask of the page rendered at [LabelCropper.RENDER_DPI].
 */
object FlipkartLayout {

    /** Ink threshold used for all Flipkart detection (gray < 250). */
    const val INK_THRESHOLD = 250

    /**
     * Full (start, end) row span -- inclusive -- of the horizontal dashed
     * cut-line separating the shipping label from a Tax Invoice printed below
     * it (e.g. Shopsy templates). A dashed rule has many short on/off
     * transitions, spans most of the page width, and has near-identical
     * density across its run of rows. Null if no such line is found.
     */
    fun findDashedRuleSpan(
        mask: InkMask,
        minTransitions: Int = 60,
        minDensityRatio: Double = 0.15,
        maxDensityRatio: Double = 0.85,
        minSpanRatio: Double = 0.6,
        minRun: Int = 2,
        startRow: Int = 0,
    ): IntRange? {
        val w = mask.width
        val h = mask.height

        // Returns the row's ink density, or -1 if the row isn't a dashed-rule candidate.
        fun candidateDensity(y: Int): Int {
            val base = y * w
            var first = -1
            var last = -1
            var density = 0
            var transitions = 0
            for (x in 0 until w) {
                val ink = mask.bits[base + x]
                if (ink) {
                    if (first < 0) first = x
                    last = x
                    density++
                }
                if (x > 0 && ink != mask.bits[base + x - 1]) transitions++
            }
            if (first < 0) return -1
            val span = last - first
            val ok = transitions >= minTransitions &&
                density >= minDensityRatio * w && density <= maxDensityRatio * w &&
                span >= minSpanRatio * w
            return if (ok) density else -1
        }

        var runStart = -1
        var runDensity = 0
        for (y in startRow until h) {
            val density = candidateDensity(y)
            if (density >= 0) {
                if (runStart < 0 || abs(density - runDensity) > max(5.0, 0.02 * w)) {
                    runStart = y
                    runDensity = density
                }
                if (y - runStart + 1 >= minRun) {
                    // Confirmed a real dashed run -- extend forward to where it ends
                    // so callers can skip past the whole line.
                    var runEnd = y
                    var yy = y + 1
                    while (yy < h && candidateDensity(yy) >= 0) {
                        runEnd = yy
                        yy++
                    }
                    return runStart..runEnd
                }
            } else {
                runStart = -1
            }
        }
        return null
    }

    /**
     * Tight content bounding box within rows [top, bottom) and columns
     * [left, right), as inclusive (c0, r0, c1, r1) in full-mask coordinates.
     * Null if the region is blank.
     */
    fun tightBox(mask: InkMask, top: Int, bottom: Int, left: Int, right: Int, threshold: Int = 20): IntArray? {
        if (bottom <= top || right <= left) return null
        val w = mask.width
        val colDensity = IntArray(right - left)
        var r0 = -1
        var r1 = -1
        for (y in top until bottom) {
            val base = y * w
            var rowDensity = 0
            for (x in left until right) {
                if (mask.bits[base + x]) {
                    rowDensity++
                    colDensity[x - left]++
                }
            }
            if (rowDensity > threshold) {
                if (r0 < 0) r0 = y
                r1 = y
            }
        }
        val c0 = colDensity.indexOfFirst { it > threshold }
        val c1 = colDensity.indexOfLast { it > threshold }
        if (r0 < 0 || c0 < 0) return null
        return intArrayOf(c0 + left, r0, c1 + left, r1)
    }

    /**
     * Crop box of the shipping label: starts just past the outer margin and
     * stops at the dashed cut-line above any tax-invoice section. Falls back
     * to an edge-trimmed content box when there is no dashed line.
     */
    fun labelBox(mask: InkMask, zoom: Double, threshold: Int = 20, trimPt: Double = 10.0): PixelRect? {
        val trimPx = (trimPt * zoom).toInt()
        val h = mask.height
        val w = mask.width
        if (h <= 2 * trimPx || w <= 2 * trimPx) return null

        val dashed = findDashedRuleSpan(mask, startRow = trimPx)
        var bottomLimit = if (dashed != null) dashed.first - 1 else h - trimPx
        if (bottomLimit <= trimPx) bottomLimit = h - trimPx

        val box = tightBox(mask, trimPx, bottomLimit, trimPx, w - trimPx, threshold) ?: return null
        return PixelRect(box[0], box[1], box[2], box[3])
    }

    /**
     * Crop box of the Tax Invoice printed below the dashed cut-line, or null
     * if the page has none (the plain Flipkart template). Uses a smaller edge
     * trim than [labelBox] because the invoice border can sit close to the
     * page edge, then pads back out by the trim so flush content isn't shaved.
     */
    fun invoiceBox(mask: InkMask, zoom: Double, threshold: Int = 20, trimPt: Double = 3.0): PixelRect? {
        val trimPx = max(1, (trimPt * zoom).toInt())
        val h = mask.height
        val w = mask.width
        if (h <= 2 * trimPx || w <= 2 * trimPx) return null

        val dashed = findDashedRuleSpan(mask, startRow = trimPx) ?: return null
        val box = tightBox(mask, dashed.last + 1, h - trimPx, trimPx, w - trimPx, threshold) ?: return null
        val pad = trimPx
        return PixelRect(
            max(0, box[0] - pad),
            max(0, box[1] - pad),
            minOf(w, box[2] + pad),
            minOf(h, box[3] + pad),
        )
    }
}
