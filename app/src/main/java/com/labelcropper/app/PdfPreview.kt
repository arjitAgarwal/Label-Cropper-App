package com.labelcropper.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Shows an output PDF's pages in a scrolling dialog. Pages are rendered as they scroll into
 * view, on a thread of their own, so a long run of labels is never all in memory at once.
 */
class PdfPreview private constructor(private val activity: Activity, private val renderer: PdfRenderer) {

    companion object {
        fun show(activity: Activity, file: File, title: CharSequence) {
            val renderer = try {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.preview_failed, Toast.LENGTH_SHORT).show()
                return
            }
            PdfPreview(activity, renderer).show(title)
        }
    }

    /** Page sizes in points, read up front so each row has its final height before it renders. */
    private val pageSizes = List(renderer.pageCount) { i -> renderer.openPage(i).use { it.width to it.height } }

    // PdfRenderer isn't thread-safe; after construction it's only touched on this thread.
    private val renderThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    @Volatile private var closed = false

    private fun show(title: CharSequence) {
        val gap = (8 * activity.resources.displayMetrics.density).toInt()
        val list = ListView(activity).apply {
            setBackgroundColor(activity.getColor(R.color.line))
            setPadding(gap, gap, gap, gap)
            clipToPadding = false
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = gap
            selector = ColorDrawable(Color.TRANSPARENT)
            adapter = PageAdapter()
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(list)
            .setPositiveButton(R.string.preview_close, null)
            .setOnDismissListener { close() }
            .show()
    }

    private fun close() {
        closed = true
        renderThread.execute {
            renderer.close()
            cache.evictAll()
        }
        renderThread.shutdown()
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageSizes.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = convertView as? ImageView ?: ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            val (pw, ph) = pageSizes[position]
            val width = (parent.width - parent.paddingLeft - parent.paddingRight)
                .takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            image.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, width * ph / pw)
            image.tag = position
            val cached = cache.get(position)
            image.setImageBitmap(cached)
            if (cached == null) render(position, width, image)
            return image
        }
    }

    private fun render(position: Int, width: Int, image: ImageView) {
        if (closed) return
        renderThread.execute {
            // Skip pages that were scrolled past before their turn came.
            if (closed || image.tag != position) return@execute
            val bitmap = cache.get(position) ?: try {
                renderer.openPage(position).use { page ->
                    val scale = width.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }.also { cache.put(position, it) }
            } catch (e: OutOfMemoryError) {
                return@execute
            }
            main.post { if (image.tag == position) image.setImageBitmap(bitmap) }
        }
    }
}
