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
}
