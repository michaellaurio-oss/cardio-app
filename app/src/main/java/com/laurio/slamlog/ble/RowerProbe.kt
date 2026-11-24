package com.laurio.slamlog.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * One-off "Rower test": finds the rower's monitor over Bluetooth, lists what it offers, and shows what
 * it sends while rowing, so we can learn how the Rogue Echo Rower's M2 monitor talks (Rogue doesn't
 * publish it). If it uses the standard Fitness Machine service (FTMS, 0x1826), its Rower Data (0x2AD1)
 * is decoded too. Read-only apart from switching notifications on; nothing is saved.
 */
@SuppressLint("MissingPermission") // Permissions are requested in MainActivity; calls are wrapped in try/catch.
class RowerProbe(context: Context) {

    data class Found(val name: String, val address: String, val rssi: Int, val ftms: Boolean, val services: List<String>)

    /** Latest value from one characteristic that sends updates. */
    data class Stream(val uuid: String, val count: Int, val hex: String)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status

    /** Services and characteristics the monitor offers, one line each. */
    private val _layout = MutableStateFlow<List<String>>(emptyList())
    val layout: StateFlow<List<String>> = _layout

    private val _streams = MutableStateFlow<Map<String, Stream>>(emptyMap())
    val streams: StateFlow<Map<String, Stream>> = _streams

    /** Decoded FTMS Rower Data (empty if the monitor doesn't use FTMS). */
    private val _rower = MutableStateFlow<Map<String, String>>(emptyMap())
    val rower: StateFlow<Map<String, String>> = _rower

    private var gatt: BluetoothGatt? = null
    private var connectedName = ""
    /** GATT allows one operation at a time: reads and notification switches wait here. */
    private val queue = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private val reads = mutableListOf<String>()

    // ---------- Scanning ----------

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            _status.value = "Turn on Bluetooth first."
            return
        }
        _found.value = emptyList()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
            _scanning.value = true
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, 20_000)
        } catch (_: SecurityException) {
            _status.value = "Allow the Nearby devices permission first."
        }
    }

    private val stopRunnable = Runnable { stopScan() }

    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids?.map { short(it.uuid) } ?: emptyList()
            val name = try { result.device.name } catch (_: SecurityException) { null }
                ?: result.scanRecord?.deviceName
            // Unnamed devices without services are phones, earbuds and the like: skip them.
            if (name.isNullOrBlank() && uuids.isEmpty()) return
            val f = Found(
                name = name ?: "(no name)",
                address = result.device.address,
                rssi = result.rssi,
                ftms = uuids.contains("1826"),
                services = uuids,
            )
            val list = _found.value.filter { it.address != f.address } + f
            // Fitness machines first, then strongest signal (nearest).
            _found.value = list.sortedWith(compareByDescending<Found> { it.ftms }.thenByDescending { it.rssi })
        }
    }

    // ---------- Connecting ----------

    fun connect(f: Found) {
        stopScan()
        disconnect()
        connectedName = f.name
        _layout.value = emptyList()
        _streams.value = emptyMap()
        _rower.value = emptyMap()
        reads.clear()
        _status.value = "Connecting to ${f.name}…"
        try {
            val device = adapter?.getRemoteDevice(f.address) ?: return
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            _status.value = "Couldn't connect. Try again."
        }
    }

    fun disconnect() {
        queue.clear()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        if (connectedName.isNotEmpty()) _status.value = "Disconnected"
    }

    private fun next() {
        val g = gatt ?: return
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            if (try { op(g) } catch (_: Exception) { false }) return // wait for its callback
        }
        _status.value = "Connected to $connectedName. Row a few strokes."
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _status.value = "Connected. Reading what it offers…"
                        try { g.discoverServices() } catch (_: SecurityException) {}
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _status.value = "Disconnected (code $status). Is the monitor on Connect App?"
                        try { g.close() } catch (_: Exception) {}
                        if (gatt === g) gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val lines = mutableListOf<String>()
                g.services.forEach { s ->
                    lines += "Service ${short(s.uuid)}${SERVICE_NAMES[short(s.uuid)]?.let { " ($it)" } ?: ""}"
                    s.characteristics.forEach { c ->
                        val id = short(c.uuid)
                        lines += "  ${id}${CHAR_NAMES[id]?.let { " ($it)" } ?: ""} [${props(c.properties)}]"
                        if ((c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0 && id in READ_THESE) {
                            queue += { gg -> @Suppress("DEPRECATION") gg.readCharacteristic(c) }
                        }
                        val notify = (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                        if (notify) queue += { gg -> enable(gg, c) }
                    }
                }
                _layout.value = lines
                next()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { gotRead(c, value); next() }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = c.value ?: ByteArray(0)
            handler.post { gotRead(c, value); next() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { next() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { gotUpdate(c, value) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            handler.post { gotUpdate(c, value) }
        }
    }

    /** Switch on notifications (or indications) for [c]. True if a descriptor write is now pending. */
    private fun enable(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return false
        val v = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, v) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun gotRead(c: BluetoothGattCharacteristic, value: ByteArray) {
        val text = value.toString(Charsets.UTF_8).filter { it.code in 32..126 }
        reads += "${CHAR_NAMES[short(c.uuid)] ?: short(c.uuid)}: $text"
        _layout.value = _layout.value.filterNot { it.startsWith("Info ") } + reads.map { "Info $it" }
    }

    private fun gotUpdate(c: BluetoothGattCharacteristic, value: ByteArray) {
        val id = short(c.uuid)
        val old = _streams.value[id]
        _streams.value = _streams.value + (id to Stream(id, (old?.count ?: 0) + 1, hex(value)))
        if (id == "2ad1") _rower.value = parseRowerData(value)
        if (id == "2acd") _rower.value = RowerManager.parseTreadmill(value, 0)?.let { t ->
            listOfNotNull(
                t.speedKmh?.let { "Speed" to "%.1f mph".format(it / 1.609344) },
                t.distanceM?.let { "Distance" to "$it m (%.2f mi)".format(it / 1609.344) },
                t.inclinePct?.let { "Incline" to "$it %" },
                t.watts?.let { "Power" to "$it W" },
            ).toMap()
        } ?: emptyMap()
    }

    /** Text to paste into a chat with Claude. */
    fun report(): String = buildString {
        appendLine("Rower test · $connectedName")
        appendLine(_status.value)
        _layout.value.forEach { appendLine(it) }
        appendLine("Updates received:")
        _streams.value.values.forEach { appendLine("  ${it.uuid}${CHAR_NAMES[it.uuid]?.let { n -> " ($n)" } ?: ""} ×${it.count}: ${it.hex}") }
        if (_rower.value.isNotEmpty()) {
            appendLine("Rower Data decoded:")
            _rower.value.forEach { (k, v) -> appendLine("  $k: $v") }
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val READ_THESE = setOf("2a29", "2a24", "2a26", "2a28", "2acc")

        private val SERVICE_NAMES = mapOf(
            "1800" to "Generic Access", "1801" to "Generic Attribute", "180a" to "Device Information",
            "180d" to "Heart Rate", "180f" to "Battery", "1826" to "Fitness Machine (FTMS)",
        )
        private val CHAR_NAMES = mapOf(
            "2a29" to "Manufacturer", "2a24" to "Model", "2a26" to "Firmware", "2a28" to "Software",
            "2acc" to "FTMS features", "2ad1" to "Rower Data", "2acd" to "Treadmill Data", "2ad3" to "Training Status",
            "2ad9" to "FTMS Control Point", "2ada" to "Machine Status", "2a37" to "Heart Rate",
            "2a19" to "Battery level",
        )

        /** "1826" for standard 16-bit Bluetooth ids, the full id otherwise. */
        fun short(u: UUID): String {
            val s = u.toString().lowercase()
            return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s
        }

        private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

        private fun props(p: Int) = listOfNotNull(
            if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) "read" else null,
            if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) "write" else null,
            if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) "write-nr" else null,
            if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) "notify" else null,
            if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) "indicate" else null,
        ).joinToString(",")

        /** Decode an FTMS Rower Data packet (Bluetooth SIG 0x2AD1). */
        fun parseRowerData(d: ByteArray): Map<String, String> {
            if (d.size < 2) return emptyMap()
            var i = 2
            val flags = (d[0].toInt() and 0xff) or ((d[1].toInt() and 0xff) shl 8)
            fun u8() = (d[i].toInt() and 0xff).also { i += 1 }
            fun u16() = ((d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8)).also { i += 2 }
            fun s16() = u16().let { if (it >= 0x8000) it - 0x10000 else it }
            fun u24() = ((d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8) or ((d[i + 2].toInt() and 0xff) shl 16)).also { i += 3 }
            fun pace(s: Int) = "%d:%02d /500 m".format(s / 60, s % 60)
            val out = linkedMapOf<String, String>()
            return try {
                if ((flags and 0x0001) == 0) { out["Stroke rate"] = "${u8() / 2.0} spm"; out["Strokes"] = "${u16()}" }
                if ((flags and 0x0002) != 0) out["Avg stroke rate"] = "${u8() / 2.0} spm"
                if ((flags and 0x0004) != 0) out["Distance"] = "${u24()} m"
                if ((flags and 0x0008) != 0) out["Pace"] = pace(u16())
                if ((flags and 0x0010) != 0) out["Avg pace"] = pace(u16())
                if ((flags and 0x0020) != 0) out["Power"] = "${s16()} W"
                if ((flags and 0x0040) != 0) out["Avg power"] = "${s16()} W"
                if ((flags and 0x0080) != 0) out["Resistance"] = "${s16()}"
                if ((flags and 0x0100) != 0) { out["Calories"] = "${u16()}"; u16(); u8() }
                if ((flags and 0x0200) != 0) out["Heart rate"] = "${u8()} bpm"
                if ((flags and 0x0400) != 0) out["METs"] = "${u8() / 10.0}"
                if ((flags and 0x0800) != 0) out["Elapsed"] = "${u16()} s"
                if ((flags and 0x1000) != 0) out["Remaining"] = "${u16()} s"
                out
            } catch (_: IndexOutOfBoundsException) {
                out.also { it["note"] = "packet shorter than its flags say" }
            }
        }
    }
}
