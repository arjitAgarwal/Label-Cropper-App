package com.labelcropper.core

import kotlin.math.roundToInt

/**
 * Meesho (Shadowfax/Delhivery-style) page analysis.
 *
 * Each page is framed by solid full-width rules:
 *   - top of the label block
 *   - between the address/barcode block and Product Details
 *   - above and below the grey "BILL OF SUPPLY/COMMERCIAL INVOICE" bar
 *   - rules inside the invoice table
 *   - bottom of the invoice
 *
 * The label side runs from the first rule down to the rule under the grey
 * Bill of Supply bar, so Product Details and the bar stay with the label;
 * the invoice side starts at "Bill To / Ship To" and runs to the last rule.
 *
 * The desktop app read these rules from the PDF's vector data. Android's
 * PdfRenderer can't expose that, so the rules are found in the rendered page
 * instead: rows with a long unbroken run of dark pixels. That also picks up
 * the bar's top border (which pdfplumber reports as a rect, not a line), so
 * the split is anchored on the shaded bar rather than on a rule index.
 */
object MeeshoLayout {

    /** Darker than the grey header bars (~240) so a bar touching a rule doesn't merge with it. */
    const val LINE_THRESHOLD = 128

    /** A detected horizontal rule; [centerY] is in pixels, [x0]/[x1] are its extent (x1 exclusive). */
    data class Rule(val centerY: Double, val x0: Int, val x1: Int)

    data class Split(val label: PixelRect, val invoice: PixelRect)

    /**
     * Horizontal rules spanning more than 85% of the page width (less 12pt
     * margins on each side), in top-to-bottom order. Runs of dark rows thicker
     * than [maxThicknessPt] are filled shapes, not rules, and are ignored.
     */
    fun findRules(img: GrayImage, zoom: Double, maxThicknessPt: Double = 3.0): List<Rule> {
        val w = img.width
        val minRun = 0.85 * (w - 24 * zoom)
        val maxThickness = maxThicknessPt * zoom
        val rules = mutableListOf<Rule>()

        var groupStart = -1
        var groupX0 = 0
        var groupX1 = 0

        fun closeGroup(endExclusive: Int) {
            if (groupStart >= 0 && endExclusive - groupStart <= maxThickness) {
                rules += Rule((groupStart + endExclusive) / 2.0, groupX0, groupX1)
            }
            groupStart = -1
        }

        for (y in 0 until img.height) {
            // Longest unbroken dark run in this row.
            val base = y * w
            var bestStart = 0
            var bestLen = 0
            var runStart = -1
            for (x in 0..w) {
                val dark = x < w && (img.pixels[base + x].toInt() and 0xFF) < LINE_THRESHOLD
                if (dark) {
                    if (runStart < 0) runStart = x
                } else if (runStart >= 0) {
                    if (x - runStart > bestLen) {
                        bestLen = x - runStart
                        bestStart = runStart
                    }
                    runStart = -1
                }
            }

            if (bestLen >= minRun) {
                if (groupStart < 0) {
                    groupStart = y
                    groupX0 = bestStart
                    groupX1 = bestStart + bestLen
                } else {
                    groupX0 = minOf(groupX0, bestStart)
                    groupX1 = maxOf(groupX1, bestStart + bestLen)
                }
            } else {
                closeGroup(y)
            }
        }
        closeGroup(img.height)
        return rules
    }

    /**
     * True if the band between two rules is mostly light-grey fill, like the
     * Bill of Supply header bar. Only the band's interior is sampled, clear of
     * the rules' own thickness.
     */
    fun isShadedBand(img: GrayImage, upper: Rule, lower: Rule, zoom: Double, maxHeightPt: Double = 40.0): Boolean {
        val inset = 3 * zoom
        val y0 = (upper.centerY + inset).toInt()
        val y1 = (lower.centerY - inset).toInt()
        if (y1 <= y0 || lower.centerY - upper.centerY > maxHeightPt * zoom) return false
        val x0 = maxOf(upper.x0, lower.x0)
        val x1 = minOf(upper.x1, lower.x1)
        if (x1 <= x0) return false

        var shaded = 0L
        var total = 0L
        for (y in y0 until y1) {
            val base = y * img.width
            for (x in x0 until x1) {
                val g = img.pixels[base + x].toInt() and 0xFF
                if (g in SHADE_MIN until SHADE_MAX) shaded++
                total++
            }
        }
        return shaded * 2 > total
    }

    private const val SHADE_MIN = 200
    private const val SHADE_MAX = 250

    /** Label and invoice crop boxes, or null if the page doesn't match the template. */
    fun findSplit(img: GrayImage, zoom: Double): Split? {
        val rules = findRules(img, zoom)
        if (rules.size < 4) return null

        // The rule under the grey Bill of Supply bar; if a template has no such
        // bar, fall back to the third rule like the desktop app.
        val splitIndex = (2 until rules.size - 1).firstOrNull { isShadedBand(img, rules[it - 1], rules[it], zoom) } ?: 2

        val left = rules.minOf { it.x0 }
        val right = rules.maxOf { it.x1 }
        val top = rules[0].centerY.roundToInt()
        val split = rules[splitIndex].centerY.roundToInt()
        val bottom = rules.last().centerY.roundToInt()

        return Split(
            label = PixelRect(left, top, right, split),
            invoice = PixelRect(left, split, right, bottom),
        )
    }
}
