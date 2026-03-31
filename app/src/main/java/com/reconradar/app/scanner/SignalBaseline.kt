package com.reconradar.app.scanner

import android.content.Context
import android.content.SharedPreferences
import com.reconradar.app.model.Anomaly
import com.reconradar.app.model.DetectedDevice
import kotlin.math.abs

/**
 * Maintains a baseline of known Wi-Fi + BLE signals and detects anomalies
 * by comparing live scan results against the stored fingerprint.
 */
class SignalBaseline(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "signal_baseline", Context.MODE_PRIVATE
    )

    private val baseline = mutableMapOf<String, BaselineEntry>()
    private val goneCandidates = mutableMapOf<String, Int>()
    private val trackerDetector = TrackerDetector

    val size: Int get() = baseline.size
    val isEmpty: Boolean get() = baseline.isEmpty()

    data class BaselineEntry(
        val id: String,
        val name: String,
        val type: DetectedDevice.DeviceType,
        val avgRssi: Int,
        val channel: Int,
        val lastSeen: Long
    )

    init {
        loadFromPrefs()
    }

    fun setBaseline(devices: List<DetectedDevice>) {
        baseline.clear()
        goneCandidates.clear()
        devices.forEach { d ->
            baseline[d.id] = BaselineEntry(
                id = d.id, name = d.name, type = d.type,
                avgRssi = d.rssi, channel = d.channel,
                lastSeen = d.timestamp
            )
        }
        saveToPrefs()
    }

    fun clear() {
        baseline.clear()
        goneCandidates.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Compare current devices against baseline. Returns anomalies detected.
     * Also flags known trackers regardless of baseline status.
     */
    fun analyze(devices: List<DetectedDevice>): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()
        val currentIds = devices.map { it.id }.toSet()

        for (device in devices) {
            // Always check for known trackers
            val tracker = trackerDetector.analyze(device)
            if (tracker != null) {
                anomalies.add(Anomaly(
                    type = Anomaly.Type.KNOWN_TRACKER,
                    device = device,
                    detail = "${tracker.trackerType.label} [${tracker.confidence}]"
                ))
            }

            if (baseline.isEmpty()) continue

            val entry = baseline[device.id]
            if (entry == null) {
                // New device not in baseline
                anomalies.add(Anomaly(
                    type = Anomaly.Type.NEW_DEVICE,
                    device = device,
                    detail = "${device.name} [${device.type.label}]"
                ))
                if (device.isOpen) {
                    anomalies.add(Anomaly(
                        type = Anomaly.Type.OPEN_NETWORK,
                        device = device,
                        detail = "Open: ${device.name}"
                    ))
                }
            } else {
                // Known device — check for deviations
                val rssiDelta = abs(device.rssi - entry.avgRssi)
                if (rssiDelta > 20) {
                    anomalies.add(Anomaly(
                        type = Anomaly.Type.RSSI_SPIKE,
                        device = device,
                        detail = "RSSI ${device.rssi - entry.avgRssi}dBm (was ${entry.avgRssi})"
                    ))
                }
                if (device.channel != entry.channel && entry.channel != 0 && device.channel != 0) {
                    anomalies.add(Anomaly(
                        type = Anomaly.Type.CHANNEL_CHANGE,
                        device = device,
                        detail = "CH ${entry.channel}->${device.channel}"
                    ))
                }
            }
        }

        // Detect gone devices (3 consecutive misses to debounce)
        if (baseline.isNotEmpty()) {
            for ((id, entry) in baseline) {
                if (id !in currentIds) {
                    val count = (goneCandidates[id] ?: 0) + 1
                    goneCandidates[id] = count
                    if (count >= 3) {
                        anomalies.add(Anomaly(
                            type = Anomaly.Type.DEVICE_GONE,
                            device = DetectedDevice(
                                id = entry.id, name = entry.name, rssi = -100,
                                type = entry.type
                            ),
                            detail = "${entry.name} disappeared"
                        ))
                    }
                } else {
                    goneCandidates.remove(id)
                }
            }
        }

        return anomalies
    }

    private fun saveToPrefs() {
        val editor = prefs.edit().clear()
        editor.putInt("count", baseline.size)
        baseline.values.forEachIndexed { i, e ->
            editor.putString("${i}_id", e.id)
            editor.putString("${i}_name", e.name)
            editor.putString("${i}_type", e.type.name)
            editor.putInt("${i}_rssi", e.avgRssi)
            editor.putInt("${i}_ch", e.channel)
            editor.putLong("${i}_seen", e.lastSeen)
        }
        editor.apply()
    }

    private fun loadFromPrefs() {
        val count = prefs.getInt("count", 0)
        for (i in 0 until count) {
            val id = prefs.getString("${i}_id", null) ?: continue
            baseline[id] = BaselineEntry(
                id = id,
                name = prefs.getString("${i}_name", "") ?: "",
                type = try {
                    DetectedDevice.DeviceType.valueOf(prefs.getString("${i}_type", "WIFI") ?: "WIFI")
                } catch (_: Exception) { DetectedDevice.DeviceType.WIFI },
                avgRssi = prefs.getInt("${i}_rssi", -70),
                channel = prefs.getInt("${i}_ch", 0),
                lastSeen = prefs.getLong("${i}_seen", 0L)
            )
        }
    }
}
