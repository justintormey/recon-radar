package com.reconradar.app.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Anomaly model: priority classification and enum contract.
 *
 * ROGUE_AP, KNOWN_TRACKER, OPEN_NETWORK, RSSI_SPIKE are all priority-3 (high priority).
 * NEW_DEVICE, CHANNEL_CHANGE are priority-2 (normal).
 * DEVICE_GONE is priority-1 (informational).
 */
class AnomalyTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun device() = DetectedDevice(
        id = "aa:bb:cc:dd:ee:ff",
        name = "TestDevice",
        rssi = -60,
        type = DetectedDevice.DeviceType.BLE
    )

    private fun anomaly(type: Anomaly.Type) = Anomaly(
        type = type,
        device = device(),
        detail = "test"
    )

    // ── High-priority anomalies (priority >= 3) ───────────────────────────────

    @Test fun `ROGUE_AP is high priority`() =
        assertTrue(anomaly(Anomaly.Type.ROGUE_AP).isHighPriority)

    @Test fun `KNOWN_TRACKER is high priority`() =
        assertTrue(anomaly(Anomaly.Type.KNOWN_TRACKER).isHighPriority)

    @Test fun `OPEN_NETWORK is high priority`() =
        assertTrue(anomaly(Anomaly.Type.OPEN_NETWORK).isHighPriority)

    @Test fun `RSSI_SPIKE is high priority`() =
        assertTrue(anomaly(Anomaly.Type.RSSI_SPIKE).isHighPriority)

    // ── Normal-priority anomalies (priority < 3) ──────────────────────────────

    @Test fun `NEW_DEVICE is not high priority`() =
        assertFalse(anomaly(Anomaly.Type.NEW_DEVICE).isHighPriority)

    @Test fun `CHANNEL_CHANGE is not high priority`() =
        assertFalse(anomaly(Anomaly.Type.CHANNEL_CHANGE).isHighPriority)

    @Test fun `DEVICE_GONE is not high priority`() =
        assertFalse(anomaly(Anomaly.Type.DEVICE_GONE).isHighPriority)

    // ── Enum contract ─────────────────────────────────────────────────────────

    @Test fun `all anomaly type labels are non-blank`() {
        Anomaly.Type.values().forEach { t ->
            assertTrue("Label for $t must be non-empty", t.label.isNotBlank())
        }
    }

    @Test fun `all anomaly type priorities are positive`() {
        Anomaly.Type.values().forEach { t ->
            assertTrue("Priority for $t must be > 0", t.priority > 0)
        }
    }

    // ── Structural properties ─────────────────────────────────────────────────

    @Test fun `anomaly stores detail string verbatim`() {
        val a = Anomaly(Anomaly.Type.NEW_DEVICE, device(), "SSID 'FreeWifi' [WiFi]")
        assertEquals("SSID 'FreeWifi' [WiFi]", a.detail)
    }

    @Test fun `anomaly stores device reference`() {
        val d = device()
        val a = Anomaly(Anomaly.Type.KNOWN_TRACKER, d, "AirTag [HIGH]")
        assertSame(d, a.device)
    }

    @Test fun `anomaly timestamp is non-zero`() {
        val a = anomaly(Anomaly.Type.RSSI_SPIKE)
        assertTrue("Timestamp should be a real epoch millis value", a.timestamp > 0)
    }
}
