package com.reconradar.app.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.reconradar.app.model.DetectedDevice

/**
 * Wi-Fi AP scanner. Android throttles foreground apps to 4 scans per 2 minutes,
 * so we pace accordingly and always deliver cached results between real scans.
 */
class WifiScanner(private val context: Context) {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((List<DetectedDevice>) -> Unit)? = null
    private var running = false
    private var scansSinceReset = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                deliverResults()
                if (running) scheduleNext()
            }
        }
    }

    fun start(onResults: (List<DetectedDevice>) -> Unit) {
        listener = onResults
        running = true
        scansSinceReset = 0

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        triggerScan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        listener = null
    }

    val isRunning: Boolean get() = running

    private fun triggerScan() {
        @Suppress("DEPRECATION")
        wifiManager.startScan()
        scansSinceReset++
    }

    private fun scheduleNext() {
        // Stay under the 4-per-2-minutes throttle. Burst the first 3 quickly,
        // then slow to ~32s intervals.
        val delay = if (scansSinceReset < 3) 5_000L else 32_000L
        handler.postDelayed({ if (running) triggerScan() }, delay)
    }

    private fun deliverResults() {
        val results = wifiManager.scanResults ?: return
        val devices = results.map { sr ->
            DetectedDevice(
                id = sr.BSSID,
                name = sr.SSID.ifEmpty { "<hidden>" },
                rssi = sr.level,
                type = DetectedDevice.DeviceType.WIFI,
                frequency = sr.frequency,
                capabilities = sr.capabilities
            )
        }
        listener?.invoke(devices)
    }
}
