package com.rover.gateway

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
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE-central.
 *
 * IMPORTANT: all BluetoothGatt and scanner operations are serialized on one
 * dedicated BLE thread. Android callbacks may arrive from different system
 * threads, so they only enqueue work on bleHandler.
 *
 * Commands are serialized too. For DRIVE, the pending DRIVE is replaced by the
 * latest command, so the joystick doesn't create a queue of stale commands.
 */
class BleClient(
    context: Context,
    private val status: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private val bleThread = HandlerThread("rover-ble").apply { start() }
    private val bleHandler = Handler(bleThread.looper)

    var onTelemetry: ((ByteArray) -> Unit)? = null
    var onConnectedChange: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var scanning = false
    private var scanAttempts = 0

    var connected: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onConnectedChange?.invoke(value)
            }
        }

    @Volatile private var active = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private var pendingDrive: ByteArray? = null

    private val listeners = CopyOnWriteArrayList<ConnectionObserver>()

    interface ConnectionObserver {
        fun onConnected()
        fun onDisconnected()
        fun onTelemetry(data: ByteArray)
        fun onStatus(text: String)
    }

    fun addObserver(o: ConnectionObserver) = listeners.add(o)

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /* ---------------- Scan/rescan ---------------- */

    private val rescanTask = object : Runnable {
        override fun run() {
            if (!active || connected || scanning || gatt != null) return

            if (!isBluetoothOn()) {
                status("BLE: adapter off, waiting...")
                bleHandler.postDelayed(this, 3000)
                return
            }

            scanAttempts++
            scanning = true
            status("BLE: scanning for ROVER...")
            adapter?.bluetoothLeScanner?.startScan(scanCallback)

            val timeout = if (scanAttempts <= 1) 15000L else 5000L
            bleHandler.postDelayed({ stopScanInternal() }, timeout)
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Search by rover name OR by service UUID (Android doesn't always return the name).
            val name = result.device.name ?: ""
            val svcOk = result.scanRecord?.serviceUuids
                ?.any { it.uuid == Protocol.Uuids.SERVICE } == true

            if (name == "ROVER-S3" || name.startsWith("ROVER") || svcOk) {
                bleHandler.post {
                    if (!active || connected || gatt != null) return@post
                    scanAttempts = 0
                    stopScanInternal()
                    connectInternal(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            bleHandler.post {
                scanning = false
                status("BLE: scan error ($errorCode)")
                scheduleReconnectInternal(2000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (scanning) {
            scanning = false
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }

        if (active && !connected && gatt == null) {
            // rover not found — retry with increasing backoff (up to 10s)
            val backoff = (scanAttempts * 1000L).coerceAtMost(10_000L)
            bleHandler.removeCallbacks(rescanTask)
            bleHandler.postDelayed(rescanTask, backoff)
        }
    }

    fun startScan() {
        bleHandler.post {
            if (active) return@post
            active = true
            scanAttempts = 0
            bleHandler.post(rescanTask)
        }
    }

    /* ---------------- Connect ---------------- */

    @SuppressLint("MissingPermission")
    private fun connectInternal(device: BluetoothDevice) {
        if (!active || gatt != null || connected) return

        status("BLE: connecting to ${device.name ?: device.address}...")
        val g = runCatching {
            device.connectGatt(null, false, gattCallback)
        }.getOrNull()

        if (g == null) {
            status("BLE: connectGatt returned null, retrying...")
            scheduleReconnectInternal(2000)
            return
        }

        gatt = g
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            bleHandler.post {
                if (gatt !== g && newState != BluetoothProfile.STATE_CONNECTED) {
                    runCatching { g.close() }
                    return@post
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                            onError?.invoke("BLE connect error (status $statusCode)")
                            closeGattInternal(g)
                            scheduleReconnectInternal(1000)
                            return@post
                        }

                        gatt = g
                        writeQueue.clear()
                        pendingDrive = null
                        writeBusy = false

                        status("BLE: connected, discovering services...")
                        val ok = runCatching { g.discoverServices() }.getOrDefault(false)
                        if (!ok) {
                            onError?.invoke("BLE discoverServices() failed")
                            closeGattInternal(g)
                            scheduleReconnectInternal(1000)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected = false
                        listeners.forEach { it.onDisconnected() }
                        closeGattInternal(g)
                        scheduleReconnectInternal(3000)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            bleHandler.post {
                if (gatt !== g) return@post

                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status("BLE: failed to discover services (code $statusCode)")
                    onError?.invoke("BLE services discovery failed (code $statusCode)")
                    closeGattInternal(g)
                    scheduleReconnectInternal(1000)
                    return@post
                }

                val svc = g.getService(Protocol.Uuids.SERVICE)
                val cmd = svc?.getCharacteristic(Protocol.Uuids.CMD)
                val telem = svc?.getCharacteristic(Protocol.Uuids.TELEMETRY)

                if (svc == null || cmd == null || telem == null) {
                    status("BLE: rover service/characteristics not found")
                    onError?.invoke("BLE rover service/characteristics missing")
                    closeGattInternal(g)
                    scheduleReconnectInternal(1000)
                    return@post
                }

                connected = true
                listeners.forEach { it.onConnected() }
                enableTelemetryInternal(g, telem)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val data = characteristic.value?.clone() ?: return
            bleHandler.post {
                if (gatt !== g || !connected) return@post
                onTelemetry?.invoke(data)
                listeners.forEach { it.onTelemetry(data) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            bleHandler.post {
                if (gatt !== g) return@post
                writeBusy = false
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    onError?.invoke("BLE write failed (status $statusCode)")
                }
                drainWriteQueue()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int,
        ) {
            bleHandler.post {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    onError?.invoke("BLE notification descriptor write failed (status $statusCode)")
                }
                if (gatt === g && connected) {
                    status("BLE: connected, telemetry active")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetryInternal(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
    ) {
        val desc = c.getDescriptor(UuidUtils.CCCD)
        if (desc == null) {
            onError?.invoke("BLE: CCCD descriptor not found")
            status("BLE: CCCD for telemetry not found")
            return
        }

        val localOk = runCatching { g.setCharacteristicNotification(c, true) }.getOrDefault(false)
        if (!localOk) {
            onError?.invoke("BLE setCharacteristicNotification failed")
            return
        }

        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = runCatching { g.writeDescriptor(desc) }.getOrDefault(false)
        if (!ok) {
            onError?.invoke("BLE writeDescriptor failed")
        }
    }

    private fun scheduleReconnectInternal(delayMs: Long) {
        if (!active) return
        bleHandler.removeCallbacks(rescanTask)
        bleHandler.postDelayed(rescanTask, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun closeGattInternal(g: BluetoothGatt? = gatt) {
        connected = false
        writeBusy = false
        writeQueue.clear()
        pendingDrive = null

        if (g != null) runCatching { g.disconnect() }
        if (g != null) runCatching { g.close() }
        if (gatt === g) gatt = null
    }

    /* ---------------- Send commands ---------------- */

    /**
     * Safe GATT queue.
     * DRIVE has latest-value semantics: if BLE is busy, the old DRIVE is
     * replaced by the new one. STOP/ACTION go into a regular queue.
     */
    fun writeCommand(data: ByteArray): Boolean {
        val copy = data.clone()
        val isDrive = copy.size >= 3 &&
            (copy[2].toInt() and 0xFF) == Protocol.CMD_DRIVE

        bleHandler.post {
            if (!active || !connected || gatt == null) return@post

            if (isDrive) {
                pendingDrive = copy
            } else {
                writeQueue.addLast(copy)
            }
            drainWriteQueue()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (!active || !connected || writeBusy) return

        val g = gatt ?: return
        val svc = g.getService(Protocol.Uuids.SERVICE) ?: return
        val c = svc.getCharacteristic(Protocol.Uuids.CMD) ?: return

        val next = when {
            writeQueue.isNotEmpty() -> writeQueue.removeFirst()
            pendingDrive != null -> pendingDrive.also { pendingDrive = null }
            else -> null
        } ?: return

        c.value = next
        writeBusy = true

        val ok = runCatching { g.writeCharacteristic(c) }.getOrDefault(false)
        if (!ok) {
            writeBusy = false
            onError?.invoke("BLE writeCharacteristic returned false")
            // Don't lose the latest DRIVE.
            if ((next[2].toInt() and 0xFF) == Protocol.CMD_DRIVE) {
                pendingDrive = next
            } else {
                writeQueue.addFirst(next)
            }
            bleHandler.postDelayed({ drainWriteQueue() }, 100)
        }
    }

    /* ---------------- Cleanup ---------------- */

    @SuppressLint("MissingPermission")
    fun close() {
        bleHandler.post {
            active = false
            bleHandler.removeCallbacksAndMessages(null)
            stopScanOnly()
            closeGattInternal()
        }

        bleThread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanOnly() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }
}

object UuidUtils {
    val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}