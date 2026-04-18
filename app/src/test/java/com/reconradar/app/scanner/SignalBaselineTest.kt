package com.reconradar.app.scanner

import com.reconradar.app.model.Anomaly
import com.reconradar.app.model.DetectedDevice
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SignalBaseline.detectRogueAps() — the pure companion function
 * is tested directly to avoid needing an Android Context.
 */
class SignalBaselineTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun wifiDevice(
        id: String,
        name: String,
        rssi: Int = -60,
        caps: String = "[WPA2-PSK-CCMP][ESS]"
    ) = DetectedDevice(
        id = id,
        name = name,
        rssi = rssi,
        type = DetectedDevice.DeviceType.WIFI,
        frequency = 2437,
        capabilities = caps
    )

    private fun bleDevice(id: String, name: String) = DetectedDevice(
        id = id,
        name = name,
        rssi = -70,
        type = DetectedDevice.DeviceType.BLE
    )

    private fun baselineEntry(id: String, name: String) = SignalBaseline.BaselineEntry(
        id = id,
        name = name,
        type = DetectedDevice.DeviceType.WIFI,
        avgRssi = -60,
        channel = 6,
        lastSeen = 0L
    )

    // ── Rogue AP detection ────────────────────────────────────────────────────

    @Test
    fun `rogue AP detected when new BSSID shares SSID with baseline entry`() {
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork"))
        val liveDevices = listOf(wifiDevice("aa:bb:cc:dd:ee:02", "HomeNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertEquals(1, anomalies.size)
        assertEquals(Anomaly.Type.ROGUE_AP, anomalies[0].type)
        assertEquals("aa:bb:cc:dd:ee:02", anomalies[0].device.id)
        assertTrue(anomalies[0].detail.contains("HomeNetwork"))
        assertTrue(anomalies[0].detail.contains("aa:bb:cc:dd:ee:02"))
    }

    @Test
    fun `known BSSID is not flagged as rogue AP`() {
        val knownId = "aa:bb:cc:dd:ee:01"
        val baseline = listOf(baselineEntry(knownId, "HomeNetwork"))
        val liveDevices = listOf(wifiDevice(knownId, "HomeNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertTrue("Known BSSID should not be flagged", anomalies.isEmpty())
    }

    @Test
    fun `different SSID does not trigger rogue AP`() {
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork"))
        val liveDevices = listOf(wifiDevice("aa:bb:cc:dd:ee:02", "NeighborNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `BLE device is never flagged as rogue AP`() {
        // BLE device with same name as a baseline WiFi AP — must be ignored
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork"))
        val liveDevices = listOf(bleDevice("aa:bb:cc:dd:ee:02", "HomeNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `blank SSID is never flagged as rogue AP`() {
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", ""))
        val liveDevices = listOf(wifiDevice("aa:bb:cc:dd:ee:02", ""))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertTrue("Hidden networks (blank SSID) should not trigger rogue AP", anomalies.isEmpty())
    }

    @Test
    fun `empty baseline returns no anomalies`() {
        val liveDevices = listOf(wifiDevice("aa:bb:cc:dd:ee:02", "HomeNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, emptyList())

        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `empty device list returns no anomalies`() {
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork"))

        val anomalies = SignalBaseline.detectRogueAps(emptyList(), baseline)

        assertTrue(anomalies.isEmpty())
    }

    @Test
    fun `multiple rogue APs detected in one scan`() {
        val baseline = listOf(
            baselineEntry("aa:bb:cc:dd:ee:01", "CorpWifi"),
            baselineEntry("aa:bb:cc:dd:ee:02", "GuestWifi")
        )
        val liveDevices = listOf(
            wifiDevice("ff:ee:dd:cc:bb:01", "CorpWifi"),
            wifiDevice("ff:ee:dd:cc:bb:02", "GuestWifi"),
            wifiDevice("11:22:33:44:55:66", "SafeNewNetwork")  // different SSID — not rogue
        )

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baseline)

        assertEquals(2, anomalies.size)
        assertTrue(anomalies.all { it.type == Anomaly.Type.ROGUE_AP })
        assertTrue(anomalies.any { it.device.id == "ff:ee:dd:cc:bb:01" })
        assertTrue(anomalies.any { it.device.id == "ff:ee:dd:cc:bb:02" })
    }

    @Test
    fun `rogue AP anomaly is high priority`() {
        val baseline = listOf(baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork"))
        val liveDevices = listOf(wifiDevice("aa:bb:cc:dd:ee:02", "HomeNetwork"))

        val anomaly = SignalBaseline.detectRogueAps(liveDevices, baseline).first()

        assertTrue("ROGUE_AP should be high priority", anomaly.isHighPriority)
    }
}
