package com.labelcropper.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.labelcropper.core.LabelCropper
import com.labelcropper.core.PageSource
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Platform { MEESHO, FLIPKART }

/** A finished output file, or null [file] when nothing was produced (e.g. no invoices found). */
class Output(val file: File?, val pageCount: Int)

class CropResult(
    val platform: Platform,
    val labels: Output,
    val invoices: Output?,
    val summary: String,
    val warnings: List<String>,
)

class NothingCroppedException : Exception()

/** Copies the chosen PDFs into app storage and runs the cropper on them. Call off the main thread. */
class CropJob(private val context: Context) {

    fun run(platform: Platform, uris: List<Uri>, progress: LabelCropper.Progress): CropResult {
        val inDir = File(context.cacheDir, "in").apply { deleteRecursively(); mkdirs() }
        val outDir = OutputProvider.outputDir(context).apply { deleteRecursively(); mkdirs() }

        val sources = mutableListOf<PageSource>()
        try {
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri) ?: "file ${i + 1}"
                val local = File(inDir, "$i.pdf")
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name")
                input.use { src -> local.outputStream().use { src.copyTo(it) } }
                sources += try {
                    PdfRendererSource(local, name)
                } catch (e: SecurityException) {
                    throw IOException("$name is password-protected. Remove the password and try again.", e)
                } catch (e: IOException) {
                    throw IOException("$name isn't a readable PDF.", e)
                }
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            return when (platform) {
                Platform.MEESHO -> meesho(
                    sources,
                    File(outDir, "meesho_labels_$stamp.pdf"),
                    File(outDir, "meesho_invoices_$stamp.pdf"),
                    progress,
                )
                Platform.FLIPKART -> flipkart(
                    sources,
                    File(outDir, "flipkart_labels_$stamp.pdf"),
                    File(outDir, "flipkart_invoices_$stamp.pdf"),
                    progress,
                )
            }
        } finally {
            sources.forEach { runCatching { it.close() } }
            inDir.deleteRecursively()
        }
    }

    private fun meesho(sources: List<PageSource>, labelsFile: File, invoicesFile: File, progress: LabelCropper.Progress): CropResult {
        val r = labelsFile.outputStream().use { labelsOut ->
            invoicesFile.outputStream().use { invoicesOut ->
                LabelCropper.cropMeesho(sources, labelsOut, invoicesOut, progress)
            }
        }
        if (r.labelPages == 0) {
            labelsFile.delete()
            invoicesFile.delete()
            throw NothingCroppedException()
        }
        val warnings = mutableListOf<String>()
        if (r.skipped.isNotEmpty()) {
            warnings += "Couldn't detect the label layout on: ${r.skipped.joinToString()}. Those page(s) were skipped."
        }
        return CropResult(
            Platform.MEESHO,
            Output(labelsFile, r.labelPages),
            Output(invoicesFile, r.invoicePages),
            "Meesho: ${plural(r.labelPages, "label")} and ${plural(r.labelPages, "invoice")} cropped.",
            warnings,
        )
    }

    private fun flipkart(sources: List<PageSource>, labelsFile: File, invoicesFile: File, progress: LabelCropper.Progress): CropResult {
        val r = labelsFile.outputStream().use { labelsOut ->
            invoicesFile.outputStream().use { invoicesOut ->
                LabelCropper.cropFlipkart(sources, labelsOut, invoicesOut, progress)
            }
        }
        if (r.labelPages == 0) {
            labelsFile.delete()
            invoicesFile.delete()
            throw NothingCroppedException()
        }
        if (r.invoicePages == 0) invoicesFile.delete()

        val warnings = mutableListOf<String>()
        if (r.skippedLabels.isNotEmpty()) {
            warnings += "Couldn't detect the label layout on: ${r.skippedLabels.joinToString()}. " +
                "Those page(s) were skipped from the labels file."
        }
        if (r.invoicePages == 0) {
            warnings += "No Tax Invoice sections were found on any page, so no invoices file was produced."
        } else if (r.skippedInvoices.isNotEmpty()) {
            warnings += "No Tax Invoice was found below the label on: ${r.skippedInvoices.joinToString()}. " +
                "That's normal if those labels have no invoice."
        }
        return CropResult(
            Platform.FLIPKART,
            Output(labelsFile, r.labelPages),
            Output(invoicesFile.takeIf { r.invoicePages > 0 }, r.invoicePages),
            "Flipkart: ${plural(r.labelPages, "label")} cropped, ${plural(r.invoicesFound, "invoice")} found.",
            warnings,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    }
}
