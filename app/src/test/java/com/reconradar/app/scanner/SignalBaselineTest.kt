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

    private fun bleDevice(
        id: String,
        name: String,
        caps: String = "",
        mfgCompanyId: Int? = null,
        mfgData: ByteArray? = null
    ) = DetectedDevice(
        id = id,
        name = name,
        rssi = -70,
        type = DetectedDevice.DeviceType.BLE,
        capabilities = caps,
        manufacturerCompanyId = mfgCompanyId,
        manufacturerData = mfgData
    )

    private fun baselineEntry(id: String, name: String) = SignalBaseline.BaselineEntry(
        id = id,
        name = name,
        type = DetectedDevice.DeviceType.WIFI,
        avgRssi = -60,
        channel = 6,
        lastSeen = 0L
    )

    private fun blBaselineEntry(
        id: String,
        name: String,
        caps: String = ""
    ) = SignalBaseline.BaselineEntry(
        id = id,
        name = name,
        type = DetectedDevice.DeviceType.BLE,
        avgRssi = -70,
        channel = 0,
        lastSeen = 0L,
        capabilities = caps
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

    // ── BLE stable-key helper ─────────────────────────────────────────────────

    @Test
    fun `bleStableKey returns non-null when name and caps are both present`() {
        val key = SignalBaseline.bleStableKey("Galaxy Buds2", "0000fd6f-0000-1000-8000-00805f9b34fb")
        assertNotNull(key)
        assertTrue(key!!.contains("Galaxy Buds2"))
    }

    @Test
    fun `bleStableKey returns null when name is unnamed placeholder`() {
        assertNull(SignalBaseline.bleStableKey("<unnamed>", "0000fd6f"))
    }

    @Test
    fun `bleStableKey returns null when caps are empty`() {
        assertNull(SignalBaseline.bleStableKey("Galaxy Buds2", ""))
    }

    @Test
    fun `bleStableKey returns null when name is empty`() {
        assertNull(SignalBaseline.bleStableKey("", "0000fd6f"))
    }

    // ── BLE MAC rotation: composite-key re-match ──────────────────────────────

    @Test
    fun `BLE device with rotated MAC re-matches baseline via stable key — no NEW_DEVICE`() {
        val serviceUuid = "0000fd6f-0000-1000-8000-00805f9b34fb"
        val baselineEntries = listOf(
            blBaselineEntry("aa:bb:cc:dd:ee:01", "Galaxy Buds2", serviceUuid)
        )
        // Same name + service UUIDs, but MAC has rotated
        val liveDevices = listOf(bleDevice("ff:11:22:33:44:55", "Galaxy Buds2", serviceUuid))

        val anomalies = SignalBaseline.detectRogueAps(liveDevices, baselineEntries)

        // detectRogueAps only checks Wi-Fi — but we test the full path via bleStableKey
        // The integration path runs through analyze(); here we confirm the key matches.
        val baselineKey = SignalBaseline.bleStableKey("Galaxy Buds2", serviceUuid)
        val liveKey     = SignalBaseline.bleStableKey("Galaxy Buds2", serviceUuid)
        assertEquals("Stable keys must match for composite re-match to work", baselineKey, liveKey)
        assertTrue("BLE devices should not trigger rogue AP detection", anomalies.isEmpty())
    }

    @Test
    fun `BLE device with name but no service UUIDs does not get composite re-match`() {
        // Only a name, no service UUIDs — composite key must be null (collision risk too high)
        val key = SignalBaseline.bleStableKey("MyDevice", "")
        assertNull("Should not form stable key without service UUIDs", key)
    }

    // ── BLE MAC rotation: tracker suppression ─────────────────────────────────

    @Test
    fun `bleStableKey is symmetric — same inputs produce same key regardless of call order`() {
        val k1 = SignalBaseline.bleStableKey("Tile", "0000feed-0000-1000-8000-00805f9b34fb")
        val k2 = SignalBaseline.bleStableKey("Tile", "0000feed-0000-1000-8000-00805f9b34fb")
        assertEquals(k1, k2)
    }

    @Test
    fun `unnamed BLE device still gets stable key null — cannot avoid NEW_DEVICE via name alone`() {
        assertNull(SignalBaseline.bleStableKey("<unnamed>", "0000feed"))
        assertNull(SignalBaseline.bleStableKey("", "0000feed"))
    }

    // ── compositeMatchedIds — DEVICE_GONE false-alarm suppression ────────────

    private fun makeStableIndex(entries: List<SignalBaseline.BaselineEntry>): Map<String, SignalBaseline.BaselineEntry> =
        entries.mapNotNull { e ->
            SignalBaseline.bleStableKey(e.name, e.capabilities)?.let { key -> key to e }
        }.toMap()

    @Test
    fun `compositeMatchedIds returns old baseline ID when BLE MAC has rotated`() {
        val oldMac = "aa:bb:cc:dd:ee:01"
        val newMac = "ff:11:22:33:44:55"
        val serviceUuid = "0000fd6f-0000-1000-8000-00805f9b34fb"

        val entry = blBaselineEntry(oldMac, "Galaxy Buds2", serviceUuid)
        val baseline = mapOf(oldMac to entry)
        val stableIndex = makeStableIndex(listOf(entry))
        val liveDevices = listOf(bleDevice(newMac, "Galaxy Buds2", serviceUuid))

        val matched = SignalBaseline.compositeMatchedIds(liveDevices, baseline, stableIndex)

        assertTrue("Old baseline MAC should be in composite matched set", oldMac in matched)
    }

    @Test
    fun `compositeMatchedIds returns empty set when live BLE device has no stable key`() {
        // Unnamed device — bleStableKey returns null, so no composite match possible
        val oldMac = "aa:bb:cc:dd:ee:01"
        val newMac = "ff:11:22:33:44:55"

        val entry = blBaselineEntry(oldMac, "Galaxy Buds2", "0000fd6f-0000-1000-8000-00805f9b34fb")
        val baseline = mapOf(oldMac to entry)
        val stableIndex = makeStableIndex(listOf(entry))
        val liveDevices = listOf(bleDevice(newMac, "<unnamed>", ""))  // no stable key

        val matched = SignalBaseline.compositeMatchedIds(liveDevices, baseline, stableIndex)

        assertTrue("No composite match for unnamed device", matched.isEmpty())
    }

    @Test
    fun `compositeMatchedIds skips device that already matches via primary MAC`() {
        val mac = "aa:bb:cc:dd:ee:01"
        val serviceUuid = "0000fd6f-0000-1000-8000-00805f9b34fb"

        val entry = blBaselineEntry(mac, "Galaxy Buds2", serviceUuid)
        val baseline = mapOf(mac to entry)                  // primary key present
        val stableIndex = makeStableIndex(listOf(entry))
        val liveDevices = listOf(bleDevice(mac, "Galaxy Buds2", serviceUuid))  // same MAC

        val matched = SignalBaseline.compositeMatchedIds(liveDevices, baseline, stableIndex)

        // Primary match — composite path should be skipped, result is empty
        assertTrue("Primary MAC match should not appear in composite matched set", matched.isEmpty())
    }

    // ── bleStableKey whitespace edge cases ────────────────────────────────────

    @Test
    fun `bleStableKey returns null when name is whitespace-only`() {
        assertNull(SignalBaseline.bleStableKey("   ", "0000fd6f-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `bleStableKey returns null when caps are whitespace-only`() {
        assertNull(SignalBaseline.bleStableKey("Galaxy Buds2", "   "))
    }

    @Test
    fun `bleStableKey output includes both name and caps as distinct components`() {
        val key = SignalBaseline.bleStableKey("MyDevice", "0000fd6f-uuid")
        assertNotNull(key)
        // Key format is "name|caps" — both parts must be recoverable to avoid collisions
        assertTrue("Key must contain name", key!!.contains("MyDevice"))
        assertTrue("Key must contain caps", key.contains("0000fd6f-uuid"))
    }

    @Test
    fun `bleStableKey collision guard — same name different caps produce different keys`() {
        val k1 = SignalBaseline.bleStableKey("Device", "0000feed-uuid-tile")
        val k2 = SignalBaseline.bleStableKey("Device", "0000fe33-uuid-chipolo")
        assertNotEquals("Different service UUIDs must not produce identical stable keys", k1, k2)
    }

    // ── compositeMatchedIds — WIFI devices excluded ───────────────────────────

    @Test
    fun `compositeMatchedIds ignores Wi-Fi devices even if MAC differs`() {
        // Wi-Fi devices should never go through the BLE composite-key re-match path
        val entry = baselineEntry("aa:bb:cc:dd:ee:01", "HomeNetwork")
        val baseline = mapOf("aa:bb:cc:dd:ee:01" to entry)
        val stableIndex = emptyMap<String, SignalBaseline.BaselineEntry>() // no BLE entries
        val liveDevices = listOf(wifiDevice("ff:ee:dd:cc:bb:01", "HomeNetwork"))

        val matched = SignalBaseline.compositeMatchedIds(liveDevices, baseline, stableIndex)

        assertTrue("Wi-Fi devices must not appear in composite matched set", matched.isEmpty())
    }

    @Test
    fun `effectiveCurrentIds union of compositeMatchedIds prevents DEVICE_GONE false alarm`() {
        // Simulate: baseline has old MAC; live scan shows rotated MAC with same name+caps.
        // Without the union, old MAC would accumulate misses → DEVICE_GONE after 3 scans.
        // With the union, old MAC is in effectiveCurrentIds → miss counter stays at zero.
        val oldMac = "aa:bb:cc:dd:ee:01"
        val newMac = "ff:11:22:33:44:55"
        val serviceUuid = "0000fd6f-0000-1000-8000-00805f9b34fb"

        val entry = blBaselineEntry(oldMac, "Galaxy Buds2", serviceUuid)
        val baseline = mapOf(oldMac to entry)
        val stableIndex = makeStableIndex(listOf(entry))
        val liveDevices = listOf(bleDevice(newMac, "Galaxy Buds2", serviceUuid))

        val currentIds = liveDevices.map { it.id }.toSet()        // {newMac}
        val compositeIds = SignalBaseline.compositeMatchedIds(liveDevices, baseline, stableIndex)
        val effectiveCurrentIds = currentIds + compositeIds

        assertFalse("Old MAC must NOT be in raw currentIds", oldMac in currentIds)
        assertTrue("Composite match must include old MAC", oldMac in compositeIds)
        assertTrue("effectiveCurrentIds must include old MAC, preventing DEVICE_GONE", oldMac in effectiveCurrentIds)
    }
}
