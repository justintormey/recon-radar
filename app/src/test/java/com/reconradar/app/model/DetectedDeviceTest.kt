package com.reconradar.app.model

import org.junit.Assert.*
import org.junit.Test

class DetectedDeviceTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun wifi(id: String = "aa:bb:cc:dd:ee:ff", rssi: Int = -60, freq: Int = 2437,
                     caps: String = "[WPA2-PSK-CCMP][ESS]") =
        DetectedDevice(id = id, name = "TestAP", rssi = rssi, type = DetectedDevice.DeviceType.WIFI,
            frequency = freq, capabilities = caps)

    private fun ble(id: String = "11:22:33:44:55:66", rssi: Int = -70) =
        DetectedDevice(id = id, name = "TestBLE", rssi = rssi, type = DetectedDevice.DeviceType.BLE)

    // ── Channel calculation ───────────────────────────────────────────────────

    @Test fun `wifi channel 1 at 2412 MHz`() = assertEquals(1, wifi(freq = 2412).channel)

    @Test fun `wifi channel 6 at 2437 MHz`() = assertEquals(6, wifi(freq = 2437).channel)

    @Test fun `wifi channel 11 at 2462 MHz`() = assertEquals(11, wifi(freq = 2462).channel)

    @Test fun `wifi 5GHz channel 36 at 5180 MHz`() = assertEquals(36, wifi(freq = 5180).channel)

    @Test fun `ble channel is always 0`() = assertEquals(0, ble().channel)

    @Test fun `unknown frequency returns channel 0`() = assertEquals(0, wifi(freq = 3000).channel)

    // ── Band ─────────────────────────────────────────────────────────────────

    @Test fun `24GHz band label`() = assertEquals("2.4G", wifi(freq = 2437).wifiBand)

    @Test fun `5GHz band label`() = assertEquals("5G", wifi(freq = 5180).wifiBand)

    @Test fun `ble has empty band`() = assertEquals("", ble().wifiBand)

    // ── Open network detection ────────────────────────────────────────────────

    @Test fun `empty capabilities is open`() = assertTrue(wifi(caps = "").isOpen)

    @Test fun `ESS-only capabilities is open`() = assertTrue(wifi(caps = "[ESS]").isOpen)

    @Test fun `WPA2 is not open`() = assertFalse(wifi(caps = "[WPA2-PSK-CCMP][ESS]").isOpen)

    @Test fun `ble is never open`() = assertFalse(ble().isOpen)

    // ── Radar distance normalization ──────────────────────────────────────────

    @Test fun `near wifi signal produces small radar distance`() {
        val d = wifi(rssi = -30).radarDistance
        assertTrue("Expected close blip, got $d", d < 0.15f)
    }

    @Test fun `far wifi signal produces large radar distance`() {
        val d = wifi(rssi = -95).radarDistance
        assertTrue("Expected far blip, got $d", d > 0.85f)
    }

    @Test fun `radar distance is clamped between 0 and 1`() {
        listOf(-10, -30, -60, -90, -110).forEach { rssi ->
            val d = wifi(rssi = rssi).radarDistance
            assertTrue("radarDistance $d out of range for rssi=$rssi", d in 0f..1f)
        }
    }

    // ── Equality / identity ───────────────────────────────────────────────────

    @Test fun `devices with same id and type are equal`() {
        val a = wifi(id = "aa:bb:cc:dd:ee:ff", rssi = -55)
        val b = wifi(id = "aa:bb:cc:dd:ee:ff", rssi = -80)
        assertEquals(a, b)
    }

    @Test fun `wifi and ble with same id string are not equal`() {
        val w = wifi(id = "aa:bb:cc:dd:ee:ff")
        val b = ble(id = "aa:bb:cc:dd:ee:ff")
        assertNotEquals(w, b)
    }

    // ── Approximate distance ──────────────────────────────────────────────────

    @Test fun `approxMeters is positive for any valid rssi`() {
        assertTrue(wifi(rssi = -60).approxMeters > 0)
        assertTrue(ble(rssi = -70).approxMeters > 0)
    }

    @Test fun `stronger signal means shorter approximate distance`() {
        val close = wifi(rssi = -40).approxMeters
        val far = wifi(rssi = -80).approxMeters
        assertTrue("close=$close should be < far=$far", close < far)
    }

    @Test fun `ble approxMeters uses different txPower from wifi — same rssi produces different distance`() {
        val wifiDist = wifi(rssi = -60).approxMeters
        val bleDist  = ble(rssi = -60).approxMeters
        // BLE uses txPower=-59, n=2.0; Wi-Fi uses txPower=-40, n=2.7 — must differ
        assertNotEquals(wifiDist, bleDist, 0.001)
    }

    // ── Radar angle ───────────────────────────────────────────────────────────

    @Test fun `radarAngle is in [0, 360)`() {
        listOf(wifi(), ble()).forEach { d ->
            val angle = d.radarAngle
            assertTrue("radarAngle $angle out of range", angle >= 0f && angle < 360f)
        }
    }

    @Test fun `radarAngle is deterministic for a given id`() {
        val a = wifi(id = "11:22:33:44:55:66").radarAngle
        val b = wifi(id = "11:22:33:44:55:66", rssi = -90).radarAngle
        assertEquals("Same id must produce same angle regardless of rssi", a, b, 0f)
    }

    @Test fun `different ids produce different angles`() {
        val a = wifi(id = "aa:bb:cc:dd:ee:01").radarAngle
        val b = wifi(id = "aa:bb:cc:dd:ee:02").radarAngle
        // Extremely unlikely (1-in-360 chance) for two sequential MACs to collide
        assertNotEquals("Distinct IDs should (almost certainly) produce distinct angles", a, b, 0f)
    }

    // ── Frequency edge cases ──────────────────────────────────────────────────

    @Test fun `frequency outside 2.4GHz and 5GHz bands returns channel 0`() {
        // 6 GHz band (6000 MHz) is not handled — returns 0
        assertEquals(0, wifi(freq = 6000).channel)
    }

    @Test fun `frequency 0 (BLE) returns channel 0`() =
        assertEquals(0, ble().channel)

    // ── BLE radar distance clamping ───────────────────────────────────────────

    @Test fun `ble radarDistance is clamped between 0 and 1`() {
        listOf(-10, -40, -70, -100, -120).forEach { rssi ->
            val d = ble(rssi = rssi).radarDistance
            assertTrue("BLE radarDistance $d out of range for rssi=$rssi", d in 0f..1f)
        }
    }
}
