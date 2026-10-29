package com.labelcropper.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.labelcropper.core.PrinterLanguage
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
    private lateinit var printerRow: TextView

    private val selected = mutableListOf<Uri>()

    /** The PDFs the shown results were cropped from, and copies of the results saved with Save. */
    private var croppedFrom = listOf<Uri>()
    private val savedCopies = mutableListOf<Uri>()

    /** Output files on screen, and those already printed; once all are printed, everything is cleaned up. */
    private var outputs = listOf<File>()
    private val printed = mutableSetOf<File>()
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
        printerRow = findViewById(R.id.printer_row)
        printerRow.setOnClickListener { if (!busy) showPrinterSettings() }
        updatePrinterRow()

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
        croppedFrom = uris
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
        outputs = listOfNotNull(r.labels.file, r.invoices?.file)
        printed.clear()
        savedCopies.clear()

        when (r.platform) {
            Platform.MEESHO -> {
                labelsCard.bind(getString(R.string.out_meesho_labels, CropJob.plural(r.labels.pageCount, "page")), r.labels.file)
                val inv = r.invoices!!
                invoicesCard.bind(getString(R.string.out_invoices, CropJob.plural(inv.pageCount, "page")), inv.file)
            }
            Platform.FLIPKART -> {
                labelsCard.bind(getString(R.string.out_flipkart_labels, CropJob.plural(r.labels.pageCount, "page")), r.labels.file)
                val inv = r.invoices
                if (inv?.file != null) {
                    invoicesCard.bind(getString(R.string.out_invoices,CropJob.plural(inv.pageCount, "page")), inv.file)
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
        private val preview: Button = view.findViewById(R.id.output_view)
        private val save: Button = view.findViewById(R.id.output_save)
        private val share: Button = view.findViewById(R.id.output_share)
        private val print: Button = view.findViewById(R.id.output_print)
        var file: File? = null
            private set

        /** Shows the card; with a null [file] the buttons are greyed out. */
        fun bind(label: String, file: File?) {
            this.file = file
            view.visibility = View.VISIBLE
            title.text = label
            title.isEnabled = file != null
            print.setText(R.string.print)
            for (b in listOf(preview, save, share, print)) b.isEnabled = file != null
            if (file == null) return
            preview.setOnClickListener { PdfPreview.show(this@MainActivity, file, label) }
            save.setOnClickListener { saveAs(file) }
            share.setOnClickListener { share(file) }
            print.setOnClickListener { print(file) }
        }

        /** Greys out Print so an accidental second tap can't print the labels again. */
        fun markPrinted() {
            print.isEnabled = false
            print.setText(R.string.printed_button)
        }

        fun hide() {
            file = null
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

    /** Prints to the saved Bluetooth label printer, setting one up on first use. */
    private fun print(file: File) {
        if (busy) return
        withBluetooth {
            val saved = savedPrinter()
            if (saved != null) printLabels(file, saved) else setUpPrinter(file) { printLabels(file, it) }
        }
    }

    private fun systemPrint(file: File) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(file.nameWithoutExtension, PdfPrintAdapter(file), null)
    }

    private fun printLabels(file: File, settings: PrinterSettings) {
        val adapter = bluetoothAdapter() ?: return
        busy = true
        updateSelection()
        progressRow.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = getString(R.string.printing_connecting, settings.name)

        val appContext = applicationContext
        worker.execute {
            var pages = 0
            val outcome = runCatching {
                LabelPrintJob(appContext, adapter).run(file, settings) { done, total ->
                    pages = total
                    if (done == 0) return@run
                    main.post {
                        progressBar.isIndeterminate = false
                        progressBar.max = total
                        progressBar.progress = done
                        progressText.text = getString(R.string.printing_pages, done, total)
                    }
                }
            }
            main.post {
                if (isDestroyed) return@post
                busy = false
                progressRow.visibility = View.GONE
                updateSelection()
                outcome.onSuccess {
                    Toast.makeText(this, getString(R.string.printed, CropJob.plural(pages, "label"), settings.name), Toast.LENGTH_SHORT).show()
                    printed += file
                    listOf(labelsCard, invoicesCard).filter { it.file == file }.forEach { it.markPrinted() }
                    if (outputs.isNotEmpty() && printed.containsAll(outputs)) cleanUpAfterPrinting()
                }.onFailure { e ->
                    if (e is PrinterNotActiveException) {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.printer_not_active_title)
                            .setMessage(getString(R.string.printer_not_active, settings.name))
                            .setPositiveButton(R.string.try_again) { _, _ -> print(file) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .setNeutralButton(R.string.printer_settings) { _, _ -> showPrinterSettings() }
                            .show()
                        return@post
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.print_failed_title)
                        .setMessage((e.message ?: e.toString()) + getString(R.string.print_failed_hint))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.printer_settings) { _, _ -> showPrinterSettings() }
                        .show()
                }
            }
        }
    }

    /**
     * Everything has been printed: delete the original PDFs, any copies saved with Save and
     * the app's own cropped files, then reset the screen for the next batch.
     */
    private fun cleanUpAfterPrinting() {
        val docs = croppedFrom + savedCopies
        croppedFrom = emptyList()
        savedCopies.clear()
        outputs = emptyList()
        printed.clear()
        selected.clear()
        results.visibility = View.GONE
        labelsCard.hide()
        invoicesCard.hide()
        updateSelection()

        val appContext = applicationContext
        worker.execute {
            val failed = docs.count { !deleteDocument(it) }
            OutputProvider.outputDir(appContext).deleteRecursively()
            main.post {
                if (isDestroyed) return@post
                val message = if (failed == 0) {
                    getString(R.string.cleaned_up, CropJob.plural(docs.size, "file"))
                } else {
                    getString(R.string.cleaned_up_partly, CropJob.plural(failed, "file"))
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Deletes a picked or saved document. Files shared in by some apps can't be deleted. */
    private fun deleteDocument(uri: Uri): Boolean = runCatching {
        DocumentsContract.isDocumentUri(this, uri) && DocumentsContract.deleteDocument(contentResolver, uri)
    }.getOrDefault(false) || runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    // --- Printer setup -------------------------------------------------------

    /**
     * Asks for the printer, then the label size, and saves both. With a [file], the picker
     * also offers the Android print dialog for printers that have a print service.
     */
    private fun setUpPrinter(file: File?, then: ((PrinterSettings) -> Unit)? = null) {
        val adapter = bluetoothAdapter() ?: return
        PrinterPicker(
            this, adapter, canScan(),
            onPicked = { address, name ->
                chooseLabelSize { size ->
                    val previous = savedPrinter()
                    val settings = PrinterSettings(address, name, size, previous?.language ?: PrinterLanguage.TSPL)
                    savePrinter(settings)
                    then?.invoke(settings)
                }
            },
            onSystemPrint = file?.let { { systemPrint(it) } },
        ).show()
    }

    private fun chooseLabelSize(onChosen: (LabelSize) -> Unit) {
        val sizes = LabelSize.entries
        val current = savedPrinter()?.labelSize ?: LabelSize.L4X6
        var choice = sizes.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_label_size)
            .setSingleChoiceItems(sizes.map { it.title }.toTypedArray(), choice) { _, which -> choice = which }
            .setPositiveButton(android.R.string.ok) { _, _ -> onChosen(sizes[choice]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseLanguage(onChosen: (PrinterLanguage) -> Unit) {
        val languages = PrinterLanguage.entries
        val names = arrayOf(getString(R.string.language_tspl), getString(R.string.language_escpos))
        var choice = languages.indexOf(savedPrinter()?.language ?: PrinterLanguage.TSPL)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_language)
            .setSingleChoiceItems(names, choice) { _, which -> choice = which }
            .setPositiveButton(android.R.string.ok) { _, _ -> onChosen(languages[choice]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPrinterSettings() {
        val saved = savedPrinter()
        if (saved == null) {
            withBluetooth { setUpPrinter(null) }
            return
        }
        val items = arrayOf(
            getString(R.string.printer_change),
            getString(R.string.choose_label_size) + ": " + saved.labelSize.title,
            getString(R.string.choose_language) + ": " + saved.language.name.replace('_', '/'),
            getString(R.string.printer_forget),
        )
        AlertDialog.Builder(this)
            .setTitle(saved.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> withBluetooth { setUpPrinter(null) }
                    1 -> chooseLabelSize { savePrinter(PrinterSettings(saved.address, saved.name, it, saved.language)) }
                    2 -> chooseLanguage { savePrinter(PrinterSettings(saved.address, saved.name, saved.labelSize, it)) }
                    3 -> {
                        prefs().edit().remove(PREF_PRINTER_ADDRESS).apply()
                        updatePrinterRow()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updatePrinterRow() {
        val saved = savedPrinter()
        printerRow.text = if (saved == null) {
            getString(R.string.printer_row_none)
        } else {
            getString(R.string.printer_row, saved.name, saved.labelSize.title.substringBefore(" ("))
        }
    }

    private fun savedPrinter(): PrinterSettings? {
        val p = prefs()
        val address = p.getString(PREF_PRINTER_ADDRESS, null) ?: return null
        return PrinterSettings(
            address,
            p.getString(PREF_PRINTER_NAME, null) ?: address,
            runCatching { LabelSize.valueOf(p.getString(PREF_LABEL_SIZE, null)!!) }.getOrDefault(LabelSize.L4X6),
            runCatching { PrinterLanguage.valueOf(p.getString(PREF_PRINTER_LANGUAGE, null)!!) }.getOrDefault(PrinterLanguage.TSPL),
        )
    }

    private fun savePrinter(s: PrinterSettings) {
        prefs().edit()
            .putString(PREF_PRINTER_ADDRESS, s.address)
            .putString(PREF_PRINTER_NAME, s.name)
            .putString(PREF_LABEL_SIZE, s.labelSize.name)
            .putString(PREF_PRINTER_LANGUAGE, s.language.name)
            .apply()
        updatePrinterRow()
    }

    // --- Bluetooth access ----------------------------------------------------

    /** Action waiting on a permission prompt or the "turn on Bluetooth" prompt. */
    private var pendingBluetooth: (() -> Unit)? = null

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) Toast.makeText(this, R.string.bluetooth_missing, Toast.LENGTH_LONG).show()
        return adapter
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canScan() =
        granted(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION)

    private fun canConnect() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Whether the optional scan/location permission was already asked for this session. */
    private var askedScanPermission = false

    /** Runs [action] once Bluetooth permissions are granted and Bluetooth is on. */
    private fun withBluetooth(action: () -> Unit) {
        bluetoothAdapter() ?: return
        val missing = bluetoothPermissions().filterNot(::granted)
        // Connecting is essential; scanning (location, on Android 11 and older) only adds nearby
        // unpaired printers to the list, so don't keep nagging for it once declined.
        if (missing.isNotEmpty() && (!canConnect() || !askedScanPermission)) {
            askedScanPermission = true
            pendingBluetooth = { withBluetoothGranted(action) }
            requestPermissions(missing.toTypedArray(), REQ_BLUETOOTH_PERMISSIONS)
            return
        }
        withBluetoothGranted(action)
    }

    @SuppressLint("MissingPermission")
    private fun withBluetoothGranted(action: () -> Unit) {
        val adapter = bluetoothAdapter() ?: return
        if (!canConnect()) {
            Toast.makeText(this, R.string.bluetooth_denied, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            pendingBluetooth = action
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BLUETOOTH_ON)
            return
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH_PERMISSIONS) return
        pendingBluetooth?.also { pendingBluetooth = null }?.invoke()
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BLUETOOTH_ON) {
            val action = pendingBluetooth
            pendingBluetooth = null
            if (resultCode == RESULT_OK) action?.invoke()
            return
        }
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
                // Recorded up front so a clean-up queued behind this save still removes the copy.
                savedCopies += dest
                worker.execute {
                    val ok = runCatching {
                        contentResolver.openOutputStream(dest, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                    main.post {
                        if (!ok) savedCopies -= dest
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
        private const val REQ_BLUETOOTH_ON = 3
        private const val REQ_BLUETOOTH_PERMISSIONS = 4
        private const val PREF_PLATFORM = "platform"
        private const val PREF_PRINTER_ADDRESS = "printer_address"
        private const val PREF_PRINTER_NAME = "printer_name"
        private const val PREF_LABEL_SIZE = "label_size"
        private const val PREF_PRINTER_LANGUAGE = "printer_language"
    }
}
