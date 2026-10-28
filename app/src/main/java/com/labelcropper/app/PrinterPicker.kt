package com.labelcropper.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.widget.ArrayAdapter
import android.widget.Toast

/**
 * Lists paired Bluetooth devices plus BLE devices found nearby, and reports the one
 * tapped. "Other printers" falls back to the Android print dialog.
 */
@SuppressLint("MissingPermission")
class PrinterPicker(
    private val activity: Activity,
    private val adapter: BluetoothAdapter,
    private val canScan: Boolean,
    private val onPicked: (address: String, name: String) -> Unit,
    private val onSystemPrint: (() -> Unit)?,
) {
    private class Entry(val address: String, val name: String, val paired: Boolean) {
        override fun toString() = if (paired) name else "$name  (nearby)"
    }

    private val entries = mutableListOf<Entry>()
    private lateinit var list: ArrayAdapter<Entry>

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName
            if (name.isNullOrBlank()) return
            val address = result.device.address
            activity.runOnUiThread {
                if (entries.none { it.address == address }) {
                    entries += Entry(address, name, paired = false)
                    list.notifyDataSetChanged()
                }
            }
        }
    }

    fun show() {
        entries += adapter.bondedDevices.orEmpty()
            .map { Entry(it.address, it.name ?: it.address, paired = true) }
            .sortedByDescending { looksLikePrinter(it.name) }
        list = ArrayAdapter(activity, android.R.layout.simple_list_item_1, entries)

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.choose_printer)
            .setAdapter(list) { _, which -> entries[which].let { onPicked(it.address, it.name) } }
            .setNegativeButton(android.R.string.cancel, null)
        if (onSystemPrint != null) builder.setNeutralButton(R.string.other_printers) { _, _ -> onSystemPrint.invoke() }
        // A message would hide the list, so the hint goes in a toast while the scan fills it.
        if (entries.isEmpty()) Toast.makeText(activity, R.string.no_paired_printers, Toast.LENGTH_LONG).show()

        val scanner = if (canScan) runCatching { adapter.bluetoothLeScanner }.getOrNull() else null
        val dialog = builder.create()
        dialog.setOnDismissListener { runCatching { scanner?.stopScan(scanCallback) } }
        dialog.show()
        runCatching { scanner?.startScan(scanCallback) }
    }

    private fun looksLikePrinter(name: String) =
        PRINTER_HINTS.any { name.contains(it, ignoreCase = true) }

    companion object {
        private val PRINTER_HINTS = listOf("print", "seznik", "shakti", "label", "tsc", "xp-", "pos", "hprt", "zebra", "bt-")
    }
}
