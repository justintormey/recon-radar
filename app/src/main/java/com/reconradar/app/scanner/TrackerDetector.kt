package com.reconradar.app.scanner

import com.reconradar.app.model.DetectedDevice

/**
 * Identifies known wireless tracker patterns in BLE advertisements.
 * Detects Apple AirTag / Find My, Tile, Samsung SmartTag, and
 * other common tracker protocols based on manufacturer data
 * company IDs and service UUID patterns.
 */
object TrackerDetector {

    data class TrackerMatch(
        val trackerType: TrackerType,
        val confidence: Confidence
    )

    enum class TrackerType(val label: String) {
        APPLE_FINDMY("Apple FindMy"),
        AIRTAG("AirTag"),
        TILE("Tile"),
        SAMSUNG_SMARTTAG("SmartTag"),
        CHIPOLO("Chipolo"),
        GENERIC_BEACON("Beacon"),
        UNKNOWN_TRACKER("Unknown Tracker")
    }

    enum class Confidence { HIGH, MEDIUM, LOW }

    // Bluetooth SIG company identifiers (little-endian in advertisements)
    private const val APPLE_COMPANY_ID = 0x004C
    private const val SAMSUNG_COMPANY_ID = 0x0075
    private const val TILE_COMPANY_ID = 0x010D

    // Apple Find My network advertisement type byte
    private const val APPLE_FINDMY_TYPE: Byte = 0x12
    // Apple Nearby Info type
    private const val APPLE_NEARBY_TYPE: Byte = 0x10

    // Known Tile service UUID prefix
    private const val TILE_SERVICE_UUID = "0000feed"
    // Chipolo service UUID
    private const val CHIPOLO_SERVICE_UUID = "0000fe33"

    /**
     * Analyze a BLE device to determine if it matches known tracker patterns.
     * Returns null if no tracker pattern matched.
     */
    fun analyze(device: DetectedDevice): TrackerMatch? {
        if (device.type != DetectedDevice.DeviceType.BLE) return null

        // Check service UUIDs first
        val serviceMatch = checkServiceUuids(device)
        if (serviceMatch != null) return serviceMatch

        // Check manufacturer data
        val mfgMatch = checkManufacturerData(device)
        if (mfgMatch != null) return mfgMatch

        // Check name patterns
        val nameMatch = checkNamePatterns(device)
        if (nameMatch != null) return nameMatch

        return null
    }

    private fun checkServiceUuids(device: DetectedDevice): TrackerMatch? {
        val caps = device.capabilities.lowercase()
        return when {
            TILE_SERVICE_UUID in caps -> TrackerMatch(TrackerType.TILE, Confidence.HIGH)
            CHIPOLO_SERVICE_UUID in caps -> TrackerMatch(TrackerType.CHIPOLO, Confidence.HIGH)
            else -> null
        }
    }

    private fun checkManufacturerData(device: DetectedDevice): TrackerMatch? {
        val data = device.manufacturerData ?: return null
        if (data.size < 2) return null

        // Manufacturer data in Android scan records doesn't include the company ID
        // (it's already keyed by it in SparseArray). But we get the raw value bytes here.
        // We check patterns in the payload itself.

        // Apple Find My / AirTag detection:
        // Apple advertisements use type 0x12 for Find My network, length varies
        if (data.size >= 3) {
            val type = data[0]
            when (type) {
                APPLE_FINDMY_TYPE -> {
                    return if (data.size in 25..30) {
                        // AirTag-sized Find My payload
                        TrackerMatch(TrackerType.AIRTAG, Confidence.HIGH)
                    } else {
                        TrackerMatch(TrackerType.APPLE_FINDMY, Confidence.HIGH)
                    }
                }
                APPLE_NEARBY_TYPE -> {
                    // Apple Nearby interaction — not a tracker, but worth noting
                    // as it reveals Apple device presence
                    return null
                }
            }
        }

        return null
    }

    private fun checkNamePatterns(device: DetectedDevice): TrackerMatch? {
        val name = device.name.lowercase()
        return when {
            name == "tile" || name.startsWith("tile-") ->
                TrackerMatch(TrackerType.TILE, Confidence.MEDIUM)
            name.contains("smarttag") ->
                TrackerMatch(TrackerType.SAMSUNG_SMARTTAG, Confidence.MEDIUM)
            name.contains("chipolo") ->
                TrackerMatch(TrackerType.CHIPOLO, Confidence.MEDIUM)
            name.startsWith("found") || name.contains("findmy") ->
                TrackerMatch(TrackerType.APPLE_FINDMY, Confidence.LOW)
            // iBeacon / Eddystone generic beacon patterns
            name.contains("beacon") || name.contains("ibeacon") ->
                TrackerMatch(TrackerType.GENERIC_BEACON, Confidence.LOW)
            else -> null
        }
    }
}
