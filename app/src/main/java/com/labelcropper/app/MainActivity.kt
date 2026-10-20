package com.labelcropper.app

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var platformGroup: RadioGroup
    private lateinit var description: TextView
    private lateinit var pickButton: Button
    private lateinit var fileList: TextView
    private lateinit var cropButton: Button
    private lateinit var progressRow: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var results: View
    private lateinit var summary: TextView
    private lateinit var labelsCard: OutputCard
    private lateinit var invoicesCard: OutputCard
    private lateinit var warnings: TextView

    private val selected = mutableListOf<Uri>()
    private var platform = Platform.MEESHO
    private var busy = false

    /** Output waiting for the system "Save as" picker to return a destination. */
    private var pendingSave: File? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        platformGroup = findViewById(R.id.platform_group)
        description = findViewById(R.id.description)
        pickButton = findViewById(R.id.pick_button)
        fileList = findViewById(R.id.file_list)
        cropButton = findViewById(R.id.crop_button)
        progressRow = findViewById(R.id.progress_row)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        results = findViewById(R.id.results)
        summary = findViewById(R.id.summary)
        labelsCard = OutputCard(findViewById(R.id.labels_card))
        invoicesCard = OutputCard(findViewById(R.id.invoices_card))
        warnings = findViewById(R.id.warnings)

        applySystemBarInsets(findViewById(R.id.root))

        platform = runCatching {
            Platform.valueOf(prefs().getString(PREF_PLATFORM, null) ?: Platform.MEESHO.name)
        }.getOrDefault(Platform.MEESHO)
        platformGroup.check(if (platform == Platform.MEESHO) R.id.platform_meesho else R.id.platform_flipkart)
        updateDescription()
        platformGroup.setOnCheckedChangeListener { _, id ->
            platform = if (id == R.id.platform_flipkart) Platform.FLIPKART else Platform.MEESHO
            prefs().edit().putString(PREF_PLATFORM, platform.name).apply()
            updateDescription()
            results.visibility = View.GONE
        }

        pickButton.setOnClickListener { pickPdfs() }
        cropButton.setOnClickListener { crop() }

        handleIncoming(intent)
        updateSelection()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!busy) {
            handleIncoming(intent)
            updateSelection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // --- Input ---------------------------------------------------------------

    /** PDFs shared into the app (Share → Label Cropper) or opened with it. */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            selected.clear()
            selected += uris
            results.visibility = View.GONE
        }
    }

    private fun pickPdfs() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQ_PICK)
    }

    private fun updateSelection() {
        if (selected.isEmpty()) {
            fileList.visibility = View.GONE
        } else {
            fileList.visibility = View.VISIBLE
            fileList.text = selected.joinToString("\n") { displayName(it) }
        }
        cropButton.isEnabled = selected.isNotEmpty() && !busy
        pickButton.isEnabled = !busy
        for (i in 0 until platformGroup.childCount) platformGroup.getChildAt(i).isEnabled = !busy
    }

    private fun updateDescription() {
        description.setText(if (platform == Platform.MEESHO) R.string.desc_meesho else R.string.desc_flipkart)
    }

    // --- Cropping ------------------------------------------------------------

    private fun crop() {
        if (busy || selected.isEmpty()) return
        busy = true
        updateSelection()
        results.visibility = View.GONE
        progressRow.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.setText(R.string.progress_reading)

        val uris = selected.toList()
        val chosen = platform
        val appContext = applicationContext
        worker.execute {
            val outcome = runCatching {
                CropJob(appContext).run(chosen, uris) { done, total ->
                    main.post {
                        progressBar.isIndeterminate = false
                        progressBar.max = total
                        progressBar.progress = done
                        progressText.text = getString(R.string.progress_pages, done, total)
                    }
                }
            }
            main.post {
                if (isDestroyed) return@post
                busy = false
                progressRow.visibility = View.GONE
                updateSelection()
                outcome.onSuccess(::showResult).onFailure(::showError)
            }
        }
    }

    private fun showResult(r: CropResult) {
        results.visibility = View.VISIBLE
        summary.text = r.summary
        summary.setTextColor(getColor(R.color.ink))

        when (r.platform) {
            Platform.MEESHO -> {
                labelsCard.bind(getString(R.string.out_meesho, CropJob.plural(r.labels.pageCount, "page")), r.labels.file)
                invoicesCard.hide()
            }
            Platform.FLIPKART -> {
                labelsCard.bind(getString(R.string.out_flipkart_labels, CropJob.plural(r.labels.pageCount, "page")), r.labels.file)
                val inv = r.invoices
                if (inv?.file != null) {
                    invoicesCard.bind(getString(R.string.out_flipkart_invoices, CropJob.plural(inv.pageCount, "page")), inv.file)
                } else {
                    invoicesCard.bind(getString(R.string.out_flipkart_no_invoices), null)
                }
            }
        }

        warnings.visibility = if (r.warnings.isEmpty()) View.GONE else View.VISIBLE
        warnings.text = r.warnings.joinToString("\n\n")
        scrollToResults()
    }

    private fun showError(e: Throwable) {
        results.visibility = View.VISIBLE
        labelsCard.hide()
        invoicesCard.hide()
        warnings.visibility = View.GONE
        summary.setTextColor(getColor(R.color.error))
        summary.text = when (e) {
            is NothingCroppedException -> getString(R.string.error_nothing_cropped)
            is OutOfMemoryError -> getString(R.string.error_memory)
            else -> e.message ?: e.toString()
        }
        scrollToResults()
    }

    private fun scrollToResults() {
        val scroll = findViewById<View>(R.id.root)
        scroll.post { scroll.scrollTo(0, results.top) }
    }

    // --- Output actions ------------------------------------------------------

    private inner class OutputCard(private val view: View) {
        private val title: TextView = view.findViewById(R.id.output_title)
        private val save: Button = view.findViewById(R.id.output_save)
        private val share: Button = view.findViewById(R.id.output_share)
        private val print: Button = view.findViewById(R.id.output_print)

        /** Shows the card; with a null [file] the buttons are greyed out. */
        fun bind(label: String, file: File?) {
            view.visibility = View.VISIBLE
            title.text = label
            title.isEnabled = file != null
            for (b in listOf(save, share, print)) b.isEnabled = file != null
            if (file == null) return
            save.setOnClickListener { saveAs(file) }
            share.setOnClickListener { share(file) }
            print.setOnClickListener { print(file) }
        }

        fun hide() {
            view.visibility = View.GONE
        }
    }

    private fun saveAs(file: File) {
        pendingSave = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, file.name)
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun share(file: File) {
        val uri = OutputProvider.uriFor(this, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, getString(R.string.share_title))
            // Don't offer sharing the cropped file back into this app.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startActivity(chooser)
    }

    private fun print(file: File) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(file.nameWithoutExtension, PdfPrintAdapter(file), null)
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK -> {
                val clip = data.clipData
                val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
                if (uris.isNotEmpty()) {
                    selected.clear()
                    selected += uris
                    results.visibility = View.GONE
                    updateSelection()
                }
            }
            REQ_SAVE -> {
                val src = pendingSave ?: return
                val dest = data.data ?: return
                pendingSave = null
                worker.execute {
                    val ok = runCatching {
                        contentResolver.openOutputStream(dest, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                    main.post {
                        Toast.makeText(this, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // --- Helpers -------------------------------------------------------------

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    /**
     * Android 15 draws apps edge-to-edge; keep content clear of the status and navigation bars.
     * On wide windows (tablets, landscape, split screen) the card is also centred in a column
     * no wider than content_max_width instead of stretching across the screen. This is done at
     * runtime because the activity handles rotation/resizes itself rather than being recreated.
     */
    private fun applySystemBarInsets(root: View) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        val bars = Rect()

        fun update() {
            val available = root.width - bars.left - bars.right
            val extra = if (root.width == 0) 0 else maxOf(0, (available - left - right - maxWidth) / 2)
            root.setPadding(
                left + bars.left + extra, top + bars.top,
                right + bars.right + extra, bottom + bars.bottom,
            )
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                bars.set(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                bars.set(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            update()
            insets
        }
        root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) root.post { update() }
        }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
        private const val PREF_PLATFORM = "platform"
    }
}
