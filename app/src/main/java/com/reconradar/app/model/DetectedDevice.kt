package com.reconradar.app.model

import kotlin.math.pow

/**
 * Unified model for any detected wireless device — Wi-Fi AP or BLE peripheral.
 */
data class DetectedDevice(
    val id: String,                  // BSSID for Wi-Fi, MAC address for BLE
    val name: String,                // SSID for Wi-Fi, device name for BLE (or "<hidden>")
    val rssi: Int,                   // signal strength in dBm
    val type: DeviceType,
    val frequency: Int = 0,          // Wi-Fi frequency in MHz (0 for BLE)
    val capabilities: String = "",   // Wi-Fi security flags or BLE service UUIDs
    val manufacturerData: ByteArray? = null, // BLE manufacturer-specific data payload
    val manufacturerCompanyId: Int? = null,  // BLE company ID from SparseArray key
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class DeviceType(val label: String) {
        WIFI("WiFi"),
        BLE("BLE")
    }

    val channel: Int get() = when {
        type == DeviceType.BLE -> 0
        frequency in 2412..2484 -> (frequency - 2412) / 5 + 1
        frequency in 5170..5825 -> (frequency - 5170) / 5 + 34
        else -> 0
    }

    val wifiBand: String get() = when {
        type == DeviceType.BLE -> ""
        frequency < 3000 -> "2.4G"
        else -> "5G"
    }

    /** Approximate distance in meters via log-distance path loss. */
    val approxMeters: Double get() {
        val txPower = if (type == DeviceType.BLE) -59.0 else -40.0
        val n = if (type == DeviceType.BLE) 2.0 else 2.7
        return 10.0.pow((txPower - rssi) / (10.0 * n))
    }

    /** Stable angle on the radar derived from device ID hash. */
    val radarAngle: Float get() = (id.hashCode().toUInt() % 360u).toFloat()

    /** Normalized 0.0 (center) to 1.0 (edge) for radar plotting. */
    val radarDistance: Float get() {
        val near = if (type == DeviceType.BLE) -40 else -30
        val far = if (type == DeviceType.BLE) -100 else -95
        val clamped = rssi.coerceIn(far, near)
        return 1f - ((clamped - far).toFloat() / (near - far).toFloat()).coerceIn(0.05f, 1f)
    }

    val isOpen: Boolean get() = type == DeviceType.WIFI &&
            (capabilities.isEmpty() || capabilities == "[ESS]")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedDevice) return false
        return id == other.id && type == other.type
    }

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()
}
