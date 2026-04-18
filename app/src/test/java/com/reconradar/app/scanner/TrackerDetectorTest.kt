package com.reconradar.app.scanner

import com.reconradar.app.model.DetectedDevice
import org.junit.Assert.*
import org.junit.Test

class TrackerDetectorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ble(
        name: String = "",
        caps: String = "",
        mfg: ByteArray? = null,
        companyId: Int? = null
    ) = DetectedDevice(
        id = "aa:bb:cc:dd:ee:ff",
        name = name,
        rssi = -70,
        type = DetectedDevice.DeviceType.BLE,
        capabilities = caps,
        manufacturerData = mfg,
        manufacturerCompanyId = companyId
    )

    private fun wifi() = DetectedDevice(
        id = "11:22:33:44:55:66",
        name = "SomeAP",
        rssi = -60,
        type = DetectedDevice.DeviceType.WIFI
    )

    // ── Wi-Fi is never a tracker ──────────────────────────────────────────────

    @Test fun `wifi device returns null`() = assertNull(TrackerDetector.analyze(wifi()))

    // ── Service UUID matching (high confidence) ───────────────────────────────

    @Test fun `tile service UUID detected`() {
        val result = TrackerDetector.analyze(ble(caps = "uuid:0000feed-0000-1000-8000-00805f9b34fb"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.TILE, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    @Test fun `chipolo service UUID detected`() {
        val result = TrackerDetector.analyze(ble(caps = "uuid:0000fe33-0000-1000-8000-00805f9b34fb"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.CHIPOLO, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    // ── Manufacturer data matching ────────────────────────────────────────────

    @Test fun `airtag-sized find my payload detected`() {
        // type=0x12 + 25 bytes total = AirTag-range payload
        val payload = ByteArray(27)
        payload[0] = 0x12.toByte()
        val result = TrackerDetector.analyze(ble(mfg = payload))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.AIRTAG, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    @Test fun `non-airtag find my payload detected as apple findmy`() {
        // type=0x12 but only 5 bytes — generic Find My device, not AirTag-sized
        val payload = ByteArray(5)
        payload[0] = 0x12.toByte()
        val result = TrackerDetector.analyze(ble(mfg = payload))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.APPLE_FINDMY, result!!.trackerType)
    }

    @Test fun `apple nearby type 0x10 returns null`() {
        val payload = ByteArray(5)
        payload[0] = 0x10.toByte()
        assertNull(TrackerDetector.analyze(ble(mfg = payload)))
    }

    @Test fun `manufacturer data too short returns null`() {
        assertNull(TrackerDetector.analyze(ble(mfg = byteArrayOf(0x12))))
    }

    // ── Company ID matching (high confidence) ─────────────────────────────────

    @Test fun `samsung company ID 0x0075 detected as SmartTag HIGH`() {
        val payload = ByteArray(4) // minimal payload
        val result = TrackerDetector.analyze(ble(mfg = payload, companyId = 0x0075))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.SAMSUNG_SMARTTAG, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    @Test fun `tile company ID 0x010D detected as Tile HIGH`() {
        val payload = ByteArray(4)
        val result = TrackerDetector.analyze(ble(mfg = payload, companyId = 0x010D))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.TILE, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    @Test fun `apple company ID 0x004C with find-my type byte detected as AirTag`() {
        val payload = ByteArray(27)
        payload[0] = 0x12.toByte()
        val result = TrackerDetector.analyze(ble(mfg = payload, companyId = 0x004C))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.AIRTAG, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.HIGH, result.confidence)
    }

    @Test fun `unknown company ID returns null from manufacturer data check`() {
        val payload = ByteArray(4)
        assertNull(TrackerDetector.analyze(ble(mfg = payload, companyId = 0x1234)))
    }

    // ── Name pattern matching (lower confidence) ──────────────────────────────

    @Test fun `exact name 'tile' detected`() {
        val result = TrackerDetector.analyze(ble(name = "Tile"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.TILE, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.MEDIUM, result.confidence)
    }

    @Test fun `name starting with 'tile-' detected`() {
        val result = TrackerDetector.analyze(ble(name = "tile-a1b2c3"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.TILE, result!!.trackerType)
    }

    @Test fun `name containing 'smarttag' detected`() {
        val result = TrackerDetector.analyze(ble(name = "Galaxy SmartTag+"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.SAMSUNG_SMARTTAG, result!!.trackerType)
    }

    @Test fun `name containing 'chipolo' detected`() {
        val result = TrackerDetector.analyze(ble(name = "Chipolo ONE"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.CHIPOLO, result!!.trackerType)
    }

    @Test fun `name starting with 'found' detected as apple findmy`() {
        val result = TrackerDetector.analyze(ble(name = "Found Item"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.APPLE_FINDMY, result!!.trackerType)
        assertEquals(TrackerDetector.Confidence.LOW, result.confidence)
    }

    @Test fun `name containing 'beacon' detected as generic beacon`() {
        val result = TrackerDetector.analyze(ble(name = "EstimoteBeacon"))
        assertNotNull(result)
        assertEquals(TrackerDetector.TrackerType.GENERIC_BEACON, result!!.trackerType)
    }

    // ── No-match cases ────────────────────────────────────────────────────────

    @Test fun `unknown ble device returns null`() {
        assertNull(TrackerDetector.analyze(ble(name = "MyHeadphones")))
    }

    @Test fun `empty ble device returns null`() {
        assertNull(TrackerDetector.analyze(ble()))
    }
}
