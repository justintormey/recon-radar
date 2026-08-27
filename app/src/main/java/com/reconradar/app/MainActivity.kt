package com.reconradar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var locationSettingsLaunched = false
    private var selectedId: String? = null // R1 scroll-wheel jog-dial selection

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
        enableImmersive()

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

    // ── R1 hardware: scroll wheel (jog dial) + immersive full-screen ──
    private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    // The R1 scroll wheel emits DPAD_UP / DPAD_DOWN. Use it as a jog dial to cycle a
    // highlighted device; its live signal shows in the status line, so you can dial to a
    // suspicious tracker and walk toward it watching the RSSI climb. Consumed here so the
    // key never leaks into view focus navigation. (The side button is the power key.)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                cycleSelection(if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sortedDevices(): List<DetectedDevice> =
        (wifiDevices + bleDevices).sortedBy { it.id }

    private fun cycleSelection(delta: Int) {
        val list = sortedDevices()
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.id == selectedId }
        val next = if (cur < 0) (if (delta > 0) 0 else list.size - 1)
                   else (((cur + delta) % list.size) + list.size) % list.size
        selectedId = list[next].id
        radarView.setSelected(selectedId)
        showSelectedInfo(list)
    }

    /** Show the selected device's live signal, or clear the selection if it vanished. */
    private fun showSelectedInfo(list: List<DetectedDevice> = sortedDevices()) {
        val dev = list.firstOrNull { it.id == selectedId }
        if (dev == null) {
            selectedId = null
            radarView.setSelected(null)
            return
        }
        val band = if (dev.wifiBand.isNotEmpty()) "  ${dev.wifiBand}" else ""
        statusText.text = "▶ ${dev.name}  ${dev.rssi}dBm$band"
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
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isLocationEnabled) {
            scanning = false
            statusText.text = getString(R.string.location_services_off)
            showLocationServicesDialog()
            return
        }

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

        // Keep the scroll-wheel selection readout live (RSSI updates as you move).
        if (selectedId != null) showSelectedInfo()
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

    // ── Location Settings Dialog ──────────────────────────────
    private fun showLocationServicesDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_off_dialog_title))
            .setMessage(getString(R.string.location_off_dialog_message))
            .setPositiveButton(getString(R.string.location_off_open_settings)) { _, _ ->
                locationSettingsLaunched = true
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(getString(R.string.location_off_dismiss), null)
            .show()
    }

    // ── Lifecycle ─────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        // If user returned from Location Settings, retry scanning automatically.
        if (locationSettingsLaunched) {
            locationSettingsLaunched = false
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (locationManager.isLocationEnabled && !scanning) {
                requestPermissionsAndStart()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiScanner.stop()
        bleScanner.stop()
        radarView.stopSweep()
    }
}
