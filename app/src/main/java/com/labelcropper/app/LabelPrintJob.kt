package com.labelcropper.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.labelcropper.core.MonoImage
import com.labelcropper.core.PrinterLanguage
import com.labelcropper.core.ThermalCommands
import java.io.File
import kotlin.math.roundToInt

/** Label stock loaded in the printer. */
enum class LabelSize(val widthMm: Int, val heightMm: Int, val title: String) {
    L4X6(100, 150, "4 × 6 in (100 × 150 mm)"),
    L3X5(76, 127, "3 × 5 in (76 × 127 mm)"),
    L4X4(100, 100, "4 × 4 in (100 × 100 mm)"),
    L3X4(75, 100, "3 × 4 in (75 × 100 mm)"),
}

class PrinterSettings(
    val address: String,
    val name: String,
    val labelSize: LabelSize,
    val language: PrinterLanguage,
)

/**
 * Sends every page of a PDF to a Bluetooth thermal printer, one label per page. Each page is
 * turned to match the label's orientation, scaled to fit and centred. Call off the main thread.
 */
class LabelPrintJob(private val context: Context, private val adapter: BluetoothAdapter) {

    fun run(file: File, settings: PrinterSettings, progress: (done: Int, total: Int) -> Unit) {
        val size = settings.labelSize
        val labelW = size.widthMm * ThermalCommands.DOTS_PER_MM
        val labelH = size.heightMm * ThermalCommands.DOTS_PER_MM

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val total = renderer.pageCount
                progress(0, total)
                BluetoothPrinter.connect(context, adapter, settings.address).use { printer ->
                    printer.write(ThermalCommands.jobStart(settings.language, size.widthMm, size.heightMm))
                    for (i in 0 until total) {
                        val image = renderer.openPage(i).use { render(it, labelW, labelH) }
                        val x = (labelW - image.width) / 2
                        val y = (labelH - image.height) / 2
                        printer.write(ThermalCommands.label(settings.language, image, x, y))
                        progress(i + 1, total)
                    }
                }
            }
        }
    }

    private fun render(page: PdfRenderer.Page, labelW: Int, labelH: Int): MonoImage {
        val pageLandscape = page.width > page.height
        val labelLandscape = labelW > labelH
        val rotate = pageLandscape != labelLandscape
        val pw = if (rotate) page.height else page.width
        val ph = if (rotate) page.width else page.height
        val scale = minOf(labelW.toFloat() / pw, labelH.toFloat() / ph)
        val w = (pw * scale).roundToInt().coerceIn(1, labelW)
        val h = (ph * scale).roundToInt().coerceIn(1, labelH)

        val matrix = Matrix().apply {
            setScale(scale, scale)
            if (rotate) {
                // Quarter turn clockwise, then shift back into the bitmap.
                postRotate(90f)
                postTranslate(w.toFloat(), 0f)
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return MonoImage.fromArgb(w, h, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
