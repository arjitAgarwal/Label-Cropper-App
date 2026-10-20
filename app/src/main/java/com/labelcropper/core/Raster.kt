package com.labelcropper.core

/** 8-bit grayscale raster of a rendered page (0 = black, 255 = white). */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** True where the pixel is darker than [threshold]. */
    fun inkMask(threshold: Int): InkMask =
        InkMask(width, height, BooleanArray(pixels.size) { (pixels[it].toInt() and 0xFF) < threshold })

    companion object {
        /** Mean of R, G and B, matching numpy's `arr[:, :, :3].mean(axis=2)` when compared to an integer threshold. */
        fun grayOf(argb: Int): Int =
            (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3
    }
}

class InkMask(val width: Int, val height: Int, val bits: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]
}

/** Opaque ARGB pixels, row-major. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }
}

/** Pixel rectangle; [right] and [bottom] are exclusive (same convention as PIL's crop box). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Rectangle in PDF points, top-down coordinates. */
data class PtRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

/** Rect inside [zone] that fits a source of size (sw, sh) with preserved aspect ratio, centered. */
fun fitRect(sw: Double, sh: Double, zone: PtRect): PtRect {
    val scale = minOf(zone.width / sw, zone.height / sh)
    val nw = sw * scale
    val nh = sh * scale
    val x0 = zone.x0 + (zone.width - nw) / 2
    val y0 = zone.y0 + (zone.height - nh) / 2
    return PtRect(x0, y0, x0 + nw, y0 + nh)
}
