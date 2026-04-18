package com.reconradar.app.scanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import com.reconradar.app.model.DetectedDevice

/**
 * BLE scanner that detects all advertising Bluetooth Low Energy devices.
 * Batches results and delivers them at a steady interval to avoid
 * overwhelming the UI with individual advertisement callbacks.
 */
class BleScanner(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((List<DetectedDevice>) -> Unit)? = null
    private var running = false

    // Accumulate devices between delivery intervals
    private val deviceMap = mutableMapOf<String, DetectedDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { processResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Scan failed — stop gracefully
            running = false
        }
    }

    val isAvailable: Boolean get() = adapter?.isEnabled == true

    fun start(onResults: (List<DetectedDevice>) -> Unit) {
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) return

        listener = onResults
        running = true
        deviceMap.clear()

        scanner = btAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0) // immediate callbacks
            .build()

        try {
            scanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        } catch (_: SecurityException) {
            running = false
            return
        }

        // Deliver batched results every 2 seconds
        scheduleDelivery()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        scanner = null
        listener = null
    }

    val isRunning: Boolean get() = running

    private fun processResult(result: ScanResult) {
        val address = result.device.address ?: return
        val name = try {
            result.device.name
        } catch (_: SecurityException) {
            null
        } ?: result.scanRecord?.deviceName ?: ""

        val mfgPair = extractManufacturerData(result.scanRecord?.manufacturerSpecificData)
        val serviceUuids = result.scanRecord?.serviceUuids
            ?.joinToString(",") { it.toString() } ?: ""

        synchronized(deviceMap) {
            deviceMap[address] = DetectedDevice(
                id = address,
                name = name.ifEmpty { "<unnamed>" },
                rssi = result.rssi,
                type = DetectedDevice.DeviceType.BLE,
                capabilities = serviceUuids,
                manufacturerData = mfgPair?.second,
                manufacturerCompanyId = mfgPair?.first
            )
        }
    }

    private fun scheduleDelivery() {
        handler.postDelayed({
            if (!running) return@postDelayed
            val snapshot: List<DetectedDevice>
            synchronized(deviceMap) {
                snapshot = deviceMap.values.toList()
                // Purge devices not seen in last 15 seconds
                val cutoff = System.currentTimeMillis() - 15_000
                deviceMap.entries.removeAll { it.value.timestamp < cutoff }
            }
            listener?.invoke(snapshot)
            scheduleDelivery()
        }, 2_000)
    }

    private fun extractManufacturerData(sparseArray: SparseArray<ByteArray>?): Pair<Int, ByteArray>? {
        if (sparseArray == null || sparseArray.size() == 0) return null
        // Preserve company ID (SparseArray key) alongside the payload bytes
        return Pair(sparseArray.keyAt(0), sparseArray.valueAt(0))
    }
}
