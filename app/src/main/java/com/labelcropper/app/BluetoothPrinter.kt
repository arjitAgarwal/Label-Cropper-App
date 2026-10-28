package com.labelcropper.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The printer couldn't be reached at all: it's off, asleep, out of range or connected elsewhere. */
class PrinterNotActiveException(message: String) : IOException(message)

/** A byte pipe to a printer. Blocking; use off the main thread. */
interface PrinterConnection : Closeable {
    fun write(data: ByteArray)
}

/**
 * Opens a connection to a Bluetooth printer. Classic (SPP) is tried first since
 * most label printers speak it; printers that are Bluetooth Low Energy only, or
 * refuse SPP, are reached over GATT instead.
 */
@SuppressLint("MissingPermission")
object BluetoothPrinter {

    fun connect(context: Context, adapter: BluetoothAdapter, address: String): PrinterConnection {
        val device = adapter.getRemoteDevice(address)
        val errors = mutableListOf<String>()
        if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
            try {
                return SppConnection.open(device)
            } catch (e: IOException) {
                errors += "Classic: ${e.message}"
            }
        }
        if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            try {
                return BleConnection.open(context, device)
            } catch (e: IOException) {
                errors += "BLE: ${e.message}"
            }
        }
        throw PrinterNotActiveException("Couldn't connect to ${device.name ?: address} (${errors.joinToString("; ")})")
    }
}

@SuppressLint("MissingPermission")
private class SppConnection(private val socket: BluetoothSocket) : PrinterConnection {

    private val out = socket.outputStream

    override fun write(data: ByteArray) {
        // Small chunks keep the printer's receive buffer from overflowing on cheap modules.
        var i = 0
        while (i < data.size) {
            val n = minOf(CHUNK, data.size - i)
            out.write(data, i, n)
            i += n
        }
        out.flush()
    }

    override fun close() {
        // flush() only hands bytes to the stack; give the tail time to reach the printer before
        // tearing the link down, or the last label gets cut off.
        runCatching { Thread.sleep(1500) }
        runCatching { out.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val CHUNK = 4096
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun open(device: BluetoothDevice): SppConnection {
            val attempts = listOf<() -> BluetoothSocket>(
                { device.createRfcommSocketToServiceRecord(SPP) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP) },
                // Some printers only answer on channel 1 without an SDP lookup.
                { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
            )
            var last: Exception? = null
            for (make in attempts) {
                val socket = runCatching(make).getOrElse { last = it as? Exception ?: Exception(it); null } ?: continue
                try {
                    socket.connect()
                    return SppConnection(socket)
                } catch (e: IOException) {
                    last = e
                    runCatching { socket.close() }
                }
            }
            throw IOException(last?.message ?: "connection failed", last)
        }
    }
}

@SuppressLint("MissingPermission")
private class BleConnection(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val events: LinkedBlockingQueue<Event>,
    mtu: Int,
) : PrinterConnection {

    private val chunk = (mtu - 3).coerceIn(20, 512)
    private val writeType =
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    override fun write(data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val piece = data.copyOfRange(i, minOf(data.size, i + chunk))
            var tries = 0
            while (!send(piece)) {
                // The stack is still busy with the previous packet.
                if (++tries > 50) throw IOException("printer stopped accepting data")
                Thread.sleep(20)
            }
            // Wait for the stack to accept the packet before queueing the next one.
            while (true) {
                when (val e = events.poll(5, TimeUnit.SECONDS)) {
                    is Event.Written -> {
                        if (e.status != BluetoothGatt.GATT_SUCCESS) throw IOException("write failed (${e.status})")
                        break
                    }
                    is Event.Disconnected -> throw IOException("printer disconnected")
                    null -> throw IOException("printer stopped responding")
                    else -> {}
                }
            }
            i += piece.size
        }
    }

    @Suppress("DEPRECATION")
    private fun send(piece: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, piece, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = piece
            gatt.writeCharacteristic(characteristic)
        }

    override fun close() {
        runCatching { Thread.sleep(500) }
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    sealed class Event {
        class Connected(val status: Int) : Event()
        object Disconnected : Event()
        class Mtu(val mtu: Int) : Event()
        class Services(val status: Int) : Event()
        class Written(val status: Int) : Event()
    }

    companion object {
        /** Write characteristics used by common printer BLE modules, in order of preference. */
        private val KNOWN = listOf(
            "0000ff02-0000-1000-8000-00805f9b34fb",
            "49535343-8841-43f4-a8d4-ecbe34729bb3",
            "0000ae01-0000-1000-8000-00805f9b34fb",
            "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
            "0000ffe1-0000-1000-8000-00805f9b34fb",
        ).map(UUID::fromString)

        /** Generic Access / Generic Attribute / Device Information: never the data channel. */
        private val STANDARD = listOf("00001800", "00001801", "0000180a")

        fun open(context: Context, device: BluetoothDevice): BleConnection {
            val events = LinkedBlockingQueue<Event>()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    events += if (newState == BluetoothProfile.STATE_CONNECTED) Event.Connected(status) else Event.Disconnected
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    events += Event.Mtu(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    events += Event.Services(status)
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    events += Event.Written(status)
                }
            }
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("couldn't start a connection")
            try {
                if (events.poll(12, TimeUnit.SECONDS) !is Event.Connected) throw IOException("no response")

                var mtu = 23
                if (gatt.requestMtu(512)) {
                    (events.poll(3, TimeUnit.SECONDS) as? Event.Mtu)?.let { mtu = it.mtu }
                }
                if (!gatt.discoverServices()) throw IOException("service discovery failed")
                val services = events.poll(10, TimeUnit.SECONDS)
                if (services !is Event.Services || services.status != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("service discovery failed")
                }

                val writable = gatt.services
                    .filter { s -> STANDARD.none { s.uuid.toString().startsWith(it) } }
                    .flatMap { it.characteristics }
                    .filter {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                val chosen = KNOWN.firstNotNullOfOrNull { uuid -> writable.firstOrNull { it.uuid == uuid } }
                    ?: writable.firstOrNull()
                    ?: throw IOException("this device has no printer data channel")
                events.clear()
                return BleConnection(gatt, chosen, events, mtu)
            } catch (e: Exception) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                throw e as? IOException ?: IOException(e.message, e)
            }
        }
    }
}
