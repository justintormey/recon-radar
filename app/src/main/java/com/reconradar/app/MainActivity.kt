package com.reconradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.reconradar.app.hud.HudOverlayView
import com.reconradar.app.hud.RadarView
import com.reconradar.app.model.Anomaly
import com.reconradar.app.model.DetectedDevice
import com.reconradar.app.scanner.BleScanner
import com.reconradar.app.scanner.SignalBaseline
import com.reconradar.app.scanner.WifiScanner

class MainActivity : AppCompatActivity() {

    private lateinit var radarView: RadarView
    private lateinit var hudOverlay: HudOverlayView
    private lateinit var btnScan: TextView
    private lateinit var btnBaseline: TextView
    private lateinit var btnClear: TextView
    private lateinit var statusText: TextView

    private lateinit var wifiScanner: WifiScanner
    private lateinit var bleScanner: BleScanner
    private lateinit var baseline: SignalBaseline

    private var wifiDevices = listOf<DetectedDevice>()
    private var bleDevices = listOf<DetectedDevice>()
    private var allAnomalies = mutableListOf<Anomaly>()
    private var scanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            results[Manifest.permission.BLUETOOTH_SCAN] == true
        } else true

        if (locationGranted) {
            beginScanning(bleGranted)
        } else {
            statusText.text = "LOCATION DENIED — cannot scan"
            Toast.makeText(this, getString(R.string.permission_rationale), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        radarView = findViewById(R.id.radar_view)
        hudOverlay = findViewById(R.id.hud_overlay)
        btnScan = findViewById(R.id.btn_scan)
        btnBaseline = findViewById(R.id.btn_baseline)
        btnClear = findViewById(R.id.btn_clear)
        statusText = findViewById(R.id.status_text)

        wifiScanner = WifiScanner(this)
        bleScanner = BleScanner(this)
        baseline = SignalBaseline(this)

        setupButtons()
        requestPermissionsAndStart()
    }

    // ── Buttons ───────────────────────────────────────────────
    private fun setupButtons() {
        btnScan.setOnClickListener {
            if (scanning) stopScanning() else requestPermissionsAndStart()
        }
        btnBaseline.setOnClickListener {
            val all = wifiDevices + bleDevices
            if (all.isNotEmpty()) {
                baseline.setBaseline(all)
                allAnomalies.clear()
                refreshHud(all)
                statusText.text = "BASELINE SET — ${baseline.size} devices"
            }
        }
        btnClear.setOnClickListener {
            baseline.clear()
            allAnomalies.clear()
            refreshHud(wifiDevices + bleDevices)
            statusText.text = "BASELINE CLEARED"
        }
    }

    // ── Permissions ───────────────────────────────────────────
    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (needed.isEmpty()) {
            beginScanning(bleAvailable = true)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ── Scanning ──────────────────────────────────────────────
    private fun beginScanning(bleAvailable: Boolean) {
        scanning = true
        radarView.startSweep()
        btnScan.text = getString(R.string.btn_pause)
        hudOverlay.isScanning = true

        wifiScanner.start { devices ->
            wifiDevices = devices
            onScanUpdate()
        }

        if (bleAvailable && bleScanner.isAvailable) {
            bleScanner.start { devices ->
                bleDevices = devices
                onScanUpdate()
            }
            statusText.text = "SCANNING WiFi + BLE"
        } else {
            statusText.text = "SCANNING WiFi (BLE unavailable)"
        }
    }

    private fun stopScanning() {
        scanning = false
        wifiScanner.stop()
        bleScanner.stop()
        radarView.stopSweep()
        btnScan.text = getString(R.string.btn_scan)
        hudOverlay.isScanning = false
        hudOverlay.refresh()
        statusText.text = getString(R.string.paused)
    }

    private fun onScanUpdate() {
        val all = wifiDevices + bleDevices
        val newAnomalies = baseline.analyze(all)

        // Accumulate, cap at 60
        allAnomalies.addAll(newAnomalies)
        if (allAnomalies.size > 60) {
            allAnomalies = allAnomalies.takeLast(60).toMutableList()
        }

        radarView.updateDevices(all, allAnomalies)
        refreshHud(all)

        // Status line
        val trackers = newAnomalies.count { it.type == Anomaly.Type.KNOWN_TRACKER }
        val otherAnom = newAnomalies.count { it.type != Anomaly.Type.KNOWN_TRACKER }
        val parts = mutableListOf("W:${wifiDevices.size} B:${bleDevices.size}")
        if (trackers > 0) parts.add("$trackers TRACKERS")
        if (otherAnom > 0) parts.add("$otherAnom ANOM")
        statusText.text = parts.joinToString(" | ")
    }

    private fun refreshHud(all: List<DetectedDevice>) {
        hudOverlay.wifiCount = wifiDevices.size
        hudOverlay.bleCount = bleDevices.size
        hudOverlay.anomalyCount = allAnomalies.count { it.type != Anomaly.Type.KNOWN_TRACKER }
        hudOverlay.trackerCount = allAnomalies.count { it.type == Anomaly.Type.KNOWN_TRACKER }
        hudOverlay.band24 = all.count { it.wifiBand == "2.4G" }
        hudOverlay.band5 = all.count { it.wifiBand == "5G" }
        hudOverlay.baselineSize = baseline.size
        hudOverlay.recentAnomalies = allAnomalies
        hudOverlay.refresh()
    }

    // ── Lifecycle ─────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        wifiScanner.stop()
        bleScanner.stop()
        radarView.stopSweep()
    }
}
