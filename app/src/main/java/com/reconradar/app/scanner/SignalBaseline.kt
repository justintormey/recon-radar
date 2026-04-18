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
        val lastSeen: Long,
        val capabilities: String = ""  // BLE service UUIDs or Wi-Fi security flags
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
                lastSeen = d.timestamp,
                capabilities = d.capabilities
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
     *
     * BLE MAC randomization suppression (Android 14+):
     *  1. Tracker suppression — if KNOWN_TRACKER fires, NEW_DEVICE is redundant noise.
     *  2. Composite-key re-match — BLE devices with a stable name + service UUIDs are
     *     re-matched against baseline entries by composite key so that a rotated MAC
     *     doesn't generate a false NEW_DEVICE anomaly.
     */
    fun analyze(devices: List<DetectedDevice>): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()
        val currentIds = devices.map { it.id }.toSet()

        // Build a secondary index: stable composite key → baseline entry (BLE only).
        // Used to re-match devices whose MAC rotated since the baseline was captured.
        val bleStableIndex: Map<String, BaselineEntry> = baseline.values
            .filter { it.type == DetectedDevice.DeviceType.BLE }
            .mapNotNull { entry ->
                bleStableKey(entry.name, entry.capabilities)?.let { key -> key to entry }
            }
            .toMap()

        // Baseline IDs confirmed present via composite key re-match this scan cycle.
        // When a BLE MAC rotates, the old baseline ID won't appear in currentIds, but
        // the physical device is still here — composite match is proof. Union these IDs
        // into effectiveCurrentIds before the DEVICE_GONE loop to stop false alarms.
        val compositeMatchedBaselineIds = mutableSetOf<String>()

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

            // Primary lookup: exact MAC/BSSID match.
            // Fallback for BLE: composite key (name + service UUIDs) to survive MAC rotation.
            val primaryEntry: BaselineEntry? = baseline[device.id]
            val entry: BaselineEntry? = primaryEntry
                ?: if (device.type == DetectedDevice.DeviceType.BLE)
                    bleStableKey(device.name, device.capabilities)?.let { bleStableIndex[it] }
                else null

            // Composite-only match: the physical device is present but its MAC rotated.
            // Record the old baseline ID so DEVICE_GONE doesn't fire for it.
            if (primaryEntry == null && entry != null) {
                compositeMatchedBaselineIds.add(entry.id)
            }

            if (entry == null) {
                // Suppress NEW_DEVICE if KNOWN_TRACKER already fired — the tracker
                // anomaly is the meaningful signal; NEW_DEVICE is noise for rotating MACs.
                val isKnownTracker = tracker != null
                if (!isKnownTracker) {
                    anomalies.add(Anomaly(
                        type = Anomaly.Type.NEW_DEVICE,
                        device = device,
                        detail = "${device.name} [${device.type.label}]"
                    ))
                }
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

        // Rogue AP: new BSSID advertising the same SSID as a known baseline AP
        if (baseline.isNotEmpty()) {
            anomalies.addAll(detectRogueAps(devices, baseline.values))
        }

        // Effective current IDs: live raw MACs + baseline IDs re-matched via composite key.
        // Without this union, a rotated-MAC device's old baseline entry accumulates 3 misses
        // and emits a spurious DEVICE_GONE. The composite match proves the device is still
        // present, so its baseline ID must be treated as seen this cycle.
        val effectiveCurrentIds = currentIds + compositeMatchedBaselineIds

        // Detect gone devices (3 consecutive misses to debounce)
        if (baseline.isNotEmpty()) {
            for ((id, entry) in baseline) {
                if (id !in effectiveCurrentIds) {
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

    companion object {

        /**
         * Builds the set of baseline IDs confirmed present via composite stable-key
         * re-match (not primary MAC match). Call this with the live device list and the
         * already-computed [bleStableIndex] to find all baseline entries whose physical
         * device is still advertising but under a rotated MAC.
         *
         * Returned IDs must be unioned into effectiveCurrentIds before the DEVICE_GONE
         * debounce loop, otherwise the old baseline MAC accumulates misses and fires a
         * spurious DEVICE_GONE after 3 scans.
         *
         * Pure function — no Android dependencies — safe to call in JVM unit tests.
         */
        fun compositeMatchedIds(
            devices: List<DetectedDevice>,
            baseline: Map<String, BaselineEntry>,
            bleStableIndex: Map<String, BaselineEntry>
        ): Set<String> {
            val result = mutableSetOf<String>()
            for (device in devices) {
                if (device.type != DetectedDevice.DeviceType.BLE) continue
                if (baseline.containsKey(device.id)) continue  // primary match — skip
                val key = bleStableKey(device.name, device.capabilities) ?: continue
                val entry = bleStableIndex[key] ?: continue
                result.add(entry.id)
            }
            return result
        }


        /**
         * Stable composite identity key for a BLE device.
         *
         * Returns a non-null key only when BOTH the device name and service UUID
         * string are non-empty and non-placeholder — combining them minimises
         * collision risk across unrelated devices that might share a generic name.
         *
         * Used to re-match baseline entries after MAC rotation (Android 14 privacy
         * feature) without requiring root or advertising-identity access.
         */
        fun bleStableKey(name: String, capabilities: String): String? {
            val n = name.trim()
            val c = capabilities.trim()
            if (n.isEmpty() || n == "<unnamed>" || c.isEmpty()) return null
            return "$n|$c"
        }

        /**
         * Pure rogue-AP detection: given a list of live devices and a snapshot of
         * baseline entries, returns anomalies for any Wi-Fi device whose SSID matches
         * a known baseline entry but whose BSSID (id) differs.
         *
         * Extracted here so it can be exercised in JVM unit tests without a Context.
         */
        fun detectRogueAps(
            devices: List<DetectedDevice>,
            baselineEntries: Collection<BaselineEntry>
        ): List<Anomaly> {
            val anomalies = mutableListOf<Anomaly>()
            val baselineIds = baselineEntries.map { it.id }.toSet()
            for (device in devices) {
                if (device.type != DetectedDevice.DeviceType.WIFI) continue
                if (device.name.isBlank()) continue
                if (device.id in baselineIds) continue  // already known BSSID — not rogue
                val knownWithSameSsid = baselineEntries.find { b ->
                    b.name == device.name && b.type == DetectedDevice.DeviceType.WIFI
                }
                if (knownWithSameSsid != null) {
                    anomalies.add(Anomaly(
                        type = Anomaly.Type.ROGUE_AP,
                        device = device,
                        detail = "SSID '${device.name}' seen on new BSSID ${device.id}"
                    ))
                }
            }
            return anomalies
        }
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
            editor.putString("${i}_caps", e.capabilities)
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
                lastSeen = prefs.getLong("${i}_seen", 0L),
                capabilities = prefs.getString("${i}_caps", "") ?: ""
            )
        }
    }
}
