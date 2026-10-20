package com.labelcropper.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.labelcropper.core.ArgbImage
import com.labelcropper.core.GrayImage
import com.labelcropper.core.LabelCropper
import com.labelcropper.core.PageSource
import com.labelcropper.core.PixelRect
import com.labelcropper.core.RenderedPage
import java.io.File
import kotlin.math.ceil

/** [PageSource] backed by Android's built-in PdfRenderer. */
class PdfRendererSource(file: File, override val name: String) : PageSource {

    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }

    override val pageCount: Int get() = renderer.pageCount

    override fun renderPage(index: Int): RenderedPage {
        val page = renderer.openPage(index)
        try {
            val zoom = LabelCropper.RENDER_ZOOM.toFloat()
            val w = ceil(page.width * zoom).toInt()
            val h = ceil(page.height * zoom).toInt()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, Matrix().apply { setScale(zoom, zoom) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val gray = ByteArray(w * h)
                val row = IntArray(w)
                for (y in 0 until h) {
                    bitmap.getPixels(row, 0, w, 0, y, w, 1)
                    val base = y * w
                    for (x in 0 until w) gray[base + x] = GrayImage.grayOf(row[x]).toByte()
                }
                return Page(page, GrayImage(w, h, gray))
            } finally {
                bitmap.recycle()
            }
        } catch (e: Throwable) {
            page.close()
            throw e
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }

    /**
     * Keeps the PdfRenderer page open so crops can be re-rendered straight
     * from the PDF at their output size, instead of downsampling the 300 DPI
     * page bitmap (which would blur small text).
     */
    private class Page(private val page: PdfRenderer.Page, override val gray: GrayImage) : RenderedPage {

        override fun crop(rect: PixelRect, width: Int, height: Int): ArgbImage {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val s = width.toFloat() / rect.width
                val zoom = LabelCropper.RENDER_ZOOM.toFloat() * s
                val matrix = Matrix().apply {
                    setScale(zoom, zoom)
                    postTranslate(-rect.left * s, -rect.top * s)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                return ArgbImage(width, height, pixels)
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() = page.close()
    }
}
