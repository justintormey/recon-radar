package com.reconradar.app.model

data class Anomaly(
    val type: Type,
    val device: DetectedDevice,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Type(val label: String, val priority: Int) {
        NEW_DEVICE("NEW", 2),
        DEVICE_GONE("GONE", 1),
        RSSI_SPIKE("RSSI+", 3),
        CHANNEL_CHANGE("CH-SHIFT", 2),
        OPEN_NETWORK("OPEN", 3),
        KNOWN_TRACKER("TRACKER", 3),
        ROGUE_AP("ROGUE-AP", 3)
    }

    val isHighPriority: Boolean get() = type.priority >= 3
}
