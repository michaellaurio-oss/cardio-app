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
import android.os.ParcelUuid
import android.os.SystemClock
import com.laurio.slamlog.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One reading from the rower's monitor (FTMS Rower Data). Null fields weren't in the packet. */
data class RowerData(
    /** Meters on the monitor's own counter (it may reset when a workout starts on the monitor). */
    val distanceM: Int?,
    val strokeRate: Double?,
    val strokes: Int?,
    /** Seconds per 500 m right now; null when not rowing. */
    val paceSec: Int?,
    val watts: Int?,
    /** SystemClock.elapsedRealtime() when it arrived. */
    val at: Long,
)

/**
 * Reads a rowing machine over the standard Bluetooth Fitness Machine service (FTMS 0x1826, Rower Data
 * 0x2AD1). The Rogue Echo Rower's M2 monitor ("ROGUE CONSOLE ……") uses it: put the monitor on
 * Connect App. Saves the rower and reconnects to it, like [HeartRateManager] does for the strap.
 */
@SuppressLint("MissingPermission") // Permissions are requested in MainActivity; calls are wrapped in try/catch.
class RowerManager(context: Context, private val store: SettingsStore) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class Device(val name: String, val address: String)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _name = MutableStateFlow(store.rowerName)
    val name: StateFlow<String?> = _name

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _data = MutableStateFlow<RowerData?>(null)
    val data: StateFlow<RowerData?> = _data

    private var gatt: BluetoothGatt? = null
    private var userDisconnected = false

    val hasSavedRower: Boolean get() = store.rowerAddress != null

    /** Connected and a reading arrived in the last 5 seconds. */
    fun isLive(): Boolean =
        _state.value == State.CONNECTED && (_data.value?.let { SystemClock.elapsedRealtime() - it.at <= 5_000 } == true)

    // ---------- Scanning ----------

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            _error.value = "Turn on Bluetooth first."
            return
        }
        _error.value = null
        _devices.value = emptyList()
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, scanCallback)
            _state.value = State.SCANNING
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, 20_000)
        } catch (_: SecurityException) {
            _error.value = "Allow the Nearby devices permission to find the rower."
        }
    }

    private val stopRunnable = Runnable { stopScan() }

    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (_state.value == State.SCANNING) _state.value = State.IDLE
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
            val ftms = record?.serviceUuids?.contains(ParcelUuid(FTMS_SERVICE)) == true
            val looksLikeRower = name != null && NAME_HINT.containsMatchIn(name)
            if (!ftms && !looksLikeRower) return
            val address = result.device.address
            if (_devices.value.none { it.address == address }) {
                _devices.value = _devices.value + Device(name ?: "Rowing machine", address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = "Bluetooth scan failed (code $errorCode). Try again."
            _state.value = State.IDLE
        }
    }

    // ---------- Connecting ----------

    fun connect(device: Device) {
        stopScan()
        store.rowerAddress = device.address
        store.rowerName = device.name
        _name.value = device.name
        open(device.address)
    }

    /** Connect to the saved rower, if there is one and we aren't connected already. */
    fun reconnect() {
        val address = store.rowerAddress ?: return
        if (gatt != null || adapter?.isEnabled != true) return
        open(address)
    }

    private fun open(address: String) {
        userDisconnected = false
        _error.value = null
        closeGatt()
        try {
            val device = adapter?.getRemoteDevice(address) ?: return
            _state.value = State.CONNECTING
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            // The monitor only accepts a connection while it's on Connect App; give up after a while.
            handler.removeCallbacks(connectTimeout)
            handler.postDelayed(connectTimeout, 20_000)
        } catch (_: SecurityException) {
            _state.value = State.IDLE
            _error.value = "Allow the Nearby devices permission to connect."
        } catch (_: IllegalArgumentException) {
            _state.value = State.IDLE
        }
    }

    private val connectTimeout = Runnable {
        if (_state.value == State.CONNECTING) {
            closeGatt()
            _state.value = State.IDLE
            _error.value = "Couldn't reach the rower. On its monitor choose Connect App, then tap Reconnect."
        }
    }

    fun disconnect() {
        userDisconnected = true
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(retry)
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        closeGatt()
        _data.value = null
        if (_state.value != State.SCANNING) _state.value = State.IDLE
    }

    fun release() {
        stopScan()
        disconnect()
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private val retry = Runnable { reconnect() }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.postDelayed({
                    try { g.discoverServices() } catch (_: SecurityException) {}
                }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    if (gatt !== g) {
                        runCatching { g.close() }
                        return@post
                    }
                    closeGatt()
                    _data.value = null
                    _state.value = State.IDLE
                    // Dropped mid-workout (monitor slept, out of range): try again in a few seconds.
                    if (!userDisconnected) handler.postDelayed(retry, 3_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(FTMS_SERVICE)?.getCharacteristic(ROWER_DATA)
            if (ch == null) {
                handler.post {
                    _error.value = "That device doesn't send rowing data."
                    disconnect()
                }
                return
            }
            try {
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
                handler.post {
                    handler.removeCallbacks(connectTimeout)
                    _state.value = State.CONNECTED
                }
            } catch (_: SecurityException) {
                handler.post { _error.value = "Allow the Nearby devices permission to read the rower." }
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == ROWER_DATA) got(value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            if (ch.uuid == ROWER_DATA) got(value)
        }
    }

    private fun got(value: ByteArray) {
        val d = parse(value, SystemClock.elapsedRealtime()) ?: return
        handler.post {
            _data.value = d
            if (_state.value != State.CONNECTED) _state.value = State.CONNECTED
        }
    }

    companion object {
        private val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val ROWER_DATA: UUID = UUID.fromString("00002ad1-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val NAME_HINT = Regex("rogue|console|row|erg|pm5", RegexOption.IGNORE_CASE)

        /** Decode an FTMS Rower Data packet (Bluetooth SIG 0x2AD1); null if it's malformed. */
        fun parse(d: ByteArray, at: Long): RowerData? {
            if (d.size < 2) return null
            var i = 2
            val flags = (d[0].toInt() and 0xff) or ((d[1].toInt() and 0xff) shl 8)
            fun u8() = (d[i].toInt() and 0xff).also { i += 1 }
            fun u16() = ((d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8)).also { i += 2 }
            fun s16() = u16().let { if (it >= 0x8000) it - 0x10000 else it }
            fun u24() = ((d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8) or
                ((d[i + 2].toInt() and 0xff) shl 16)).also { i += 3 }
            var spm: Double? = null
            var strokes: Int? = null
            var dist: Int? = null
            var pace: Int? = null
            var watts: Int? = null
            return try {
                // Bit 0 is inverted: 0 means stroke rate and stroke count are present.
                if ((flags and 0x0001) == 0) { spm = u8() / 2.0; strokes = u16() }
                if ((flags and 0x0002) != 0) u8() // average stroke rate
                if ((flags and 0x0004) != 0) dist = u24()
                if ((flags and 0x0008) != 0) pace = u16()
                if ((flags and 0x0010) != 0) u16() // average pace
                if ((flags and 0x0020) != 0) watts = s16()
                RowerData(dist, spm, strokes, pace?.takeIf { it in 1..1200 }, watts, at)
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }
    }
}
