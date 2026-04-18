# On-Device Verification Guide — Recon Radar
**Issue #6 | Target: Samsung Galaxy S24, Android 14 (One UI 6.x)**

---

## Overview

This guide enables a human tester to verify all four functional areas end-to-end:
1. Wi-Fi AP scanning
2. BLE device scanning
3. Tracker detection (AirTag, Tile, SmartTag, Chipolo)
4. Anomaly display (radar blips + HUD ticker)

The guide is derived from static code analysis. Every test step maps to specific code paths; the "Code ref" column cites the exact class and mechanism under test.

---

## Prerequisites

### 1. Get the APK

Download `app-debug.apk` from GitHub Actions:
1. Go to https://github.com/justintormey/recon-radar/actions
2. Open the most recent **"Build Sideloadable APK"** run on `main`
3. Download artifact **`recon-radar-debug`** → unzip → `app-debug.apk`

Alternatively build locally:
```bash
cd <repo>
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 2. Enable Sideloading on Samsung S24

```
Settings → About Phone → Software Information
  → tap "Build Number" 7 times → Developer mode enabled
Settings → Developer Options → USB Debugging → ON
Settings → Apps → Special App Access → Install Unknown Apps
  → allow your file manager or Chrome
```

### 3. Install

**Via ADB (recommended — cleaner permision flow):**
```bash
adb install app-debug.apk
```

**Via file transfer:**
Copy APK to device, tap to install, allow installation from unknown source when prompted.

### 4. Pre-test device settings

| Setting | Required state | Location |
|---------|---------------|----------|
| Location services | **ON** (not just permission — system Location) | Settings → Location |
| Wi-Fi | **ON** (no need to be connected to any network) | Quick panel |
| Bluetooth | **ON** | Quick panel |
| Battery Optimization | **Unrestricted** for Recon Radar | Settings → Battery → Background usage limits |
| Screen timeout | Any — app uses `FLAG_KEEP_SCREEN_ON` | N/A |

> ⚠️ **Samsung gotcha:** Location services must be enabled at the system level, not just permission granted. Wi-Fi scan results return empty if Location is OFF, even with `ACCESS_FINE_LOCATION` granted. This is a Samsung/Android enforcement, not a bug.

---

## Test Suites

---

### Suite A — Installation & Permissions

**A1. Clean install — permission dialog**
1. Launch app (first time)
2. **Expected:** System permission dialog requests `Fine Location` + `Bluetooth Scan` + `Bluetooth Connect`
3. Grant all three
4. **Expected:** Status bar at bottom shows `SCANNING WiFi + BLE`
5. **Expected:** Radar sweep line begins rotating
- Code ref: `MainActivity.requestPermissionsAndStart()` → `permissionLauncher`

**A2. Permission denied — location**
1. Uninstall, reinstall
2. At permission dialog, deny Location
3. **Expected:** Status shows `LOCATION DENIED — cannot scan`
4. **Expected:** Toast with rationale message appears
5. **Expected:** Radar does NOT sweep
- Code ref: `permissionLauncher` result handler → `statusText.text = "LOCATION DENIED…"`

**A3. Bluetooth-only denial**
1. Uninstall, reinstall
2. Grant Location, deny Bluetooth permissions
3. **Expected:** Status shows `SCANNING WiFi (BLE unavailable)`
4. **Expected:** Wi-Fi blips appear; no cyan BLE blips
- Code ref: `beginScanning()` → `bleAvailable && bleScanner.isAvailable` guard

**A4. Re-launch with permissions already granted**
1. Close app (back button or swipe away)
2. Re-open
3. **Expected:** Scanning resumes immediately with no permission dialogs
- Code ref: `requestPermissionsAndStart()` → `needed.isEmpty()` → `beginScanning()`

---

### Suite B — Wi-Fi Scanning

**B1. APs appear on radar**
1. Open app, allow all permissions
2. Wait up to 10 seconds (first scan)
3. **Expected:** Green blips appear on radar at varying distances from center
4. **Expected:** HUD top-right shows `W:N` where N ≥ 1
5. **Expected:** Bottom bar shows `2.4G:X  5G:Y` counts
- Code ref: `WifiScanner.deliverResults()` → `DetectedDevice(type=WIFI)`

**B2. Scan cadence — throttle behavior**
1. Watch status text updates
2. **Expected:** First 3 scans fire within ~15 seconds (5s intervals)
3. After 3rd scan, **expected:** updates slow to ~32-second intervals
4. **Expected:** Counts do NOT go to zero between updates — cached results persist
- Code ref: `WifiScanner.scheduleNext()` — `if (scansSinceReset < 3) 5_000L else 32_000L`

> **Note:** If `startScan()` returns `false` (throttled by OS), the app still delivers cached results via `wifiManager.scanResults`. No crash expected. Samsung Developer Options → "Wi-Fi scan throttling" can be disabled during testing to get live results every 5s.

**B3. Hidden SSID handling**
1. If a network with a hidden SSID is in range (or configure one)
2. **Expected:** Blip appears on radar with name `<hidden>` instead of blank
- Code ref: `WifiScanner.deliverResults()` → `sr.SSID.ifEmpty { "<hidden>" }`

**B4. Band classification**
1. Open radar, look at bottom HUD bar
2. **Expected:** `2.4G:X` count matches count of blips for APs on 2412–2484 MHz
3. **Expected:** `5G:Y` count matches count of blips for APs on 3000+ MHz
- Code ref: `DetectedDevice.wifiBand` — `frequency < 3000 → "2.4G"`

**B5. RSSI / distance mapping**
1. Note a strong Wi-Fi AP (your home router)
2. **Expected:** Blip appears near center of radar (strong signal)
3. Move 20+ meters away from it
4. **Expected:** Blip migrates toward outer ring
- Code ref: `DetectedDevice.radarDistance` — near=-30dBm maps to center, far=-95dBm to edge

---

### Suite C — BLE Scanning

**C1. BLE devices appear**
1. Have at least one BLE-advertising device nearby (headphones, smartwatch, another phone with BT on)
2. **Expected:** Cyan blips appear within 4–6 seconds
3. **Expected:** HUD top-right shows `B:N` where N ≥ 1
- Code ref: `BleScanner.processResult()` → `DetectedDevice(type=BLE)`; delivery every 2s

**C2. BLE 15-second expiry**
1. Note BLE device count
2. Power off the BLE device
3. **Expected:** Within ~17 seconds (15s expiry + 2s delivery cycle) the device disappears
4. **Expected:** Blip turns grey briefly if baseline is set (GONE anomaly)
- Code ref: `BleScanner.scheduleDelivery()` → `deviceMap.entries.removeAll { it.value.timestamp < cutoff }`; cutoff = 15,000ms

**C3. Unnamed BLE device**
1. If any BLE device advertises without a device name
2. **Expected:** Blip appears labeled `<unnamed>` on radar (if RSSI > -60 or flagged)
- Code ref: `BleScanner.processResult()` → `name.ifEmpty { "<unnamed>" }`

**C4. SCAN_MODE_LOW_LATENCY active**
1. Check BLE scan latency by timing first BLE device to appear after app launch
2. **Expected:** First BLE device appears within 2–4 seconds (low-latency mode + 2s batch window)
- Code ref: `BleScanner.start()` → `ScanSettings.SCAN_MODE_LOW_LATENCY`

> **Samsung S24 note:** One UI 6 may throttle BLE scans when battery saver is active. Ensure battery mode is Performance or Balanced, not Power Saving, for this test.

**C5. MAC randomization awareness** ⚠️ *Known behavioral nuance*
1. Run scan, note BLE device count
2. Wait 5+ minutes
3. Some BLE devices (especially Samsung earbuds, Wear OS watches) will rotate MAC addresses
4. **Expected (current behavior):** Rotated devices appear as NEW entries in `deviceMap` — old address expires after 15s
5. **Expected (no crash):** App handles this gracefully; no ANR or exception
- Code ref: `BleScanner.deviceMap` is keyed by `device.address` — rotation creates a new key
- **⚠️ Potential issue:** A baseline set before rotation will permanently flag the new address as `NEW_DEVICE`. This is by design (privacy-rotating devices should look "new") but can cause anomaly noise.

---

### Suite D — Tracker Detection

> **Required hardware:** At least one of: Apple AirTag, Tile tracker, Samsung SmartTag, Chipolo. Alternatively, most tests can be validated by analyzing Bluetooth advertisements with a BLE scanner tool (like nRF Connect) to confirm the advertisement format.

**D1. AirTag detection — HIGH confidence**
1. Place an AirTag within 5 meters of the phone
2. **Expected:** Within ~4 seconds, magenta blip appears on radar
3. **Expected:** HUD shows `TRK:1`
4. **Expected:** Anomaly ticker shows `[TRACKER] AirTag [HIGH]`
5. **Expected:** Tracker count in status bar: `1 TRACKERS`
- Detection path: `TrackerDetector.checkManufacturerData()` → `companyId == APPLE_COMPANY_ID (0x004C)` → `checkApplePayload()` → `data[0] == 0x12` + payload size 25–30 bytes → `AIRTAG HIGH`
- Code ref: `TrackerDetector.kt` lines 86–96

**D2. Apple FindMy (non-AirTag) detection — HIGH confidence**
1. Place an Apple AirPods (gen 2+) or non-AirTag FindMy accessory nearby
2. **Expected:** Magenta blip with label `Apple FindMy [HIGH]`
- Detection path: Same Apple company ID path → `checkApplePayload()` → `data[0] == 0x12` + payload size NOT 25–30 → `APPLE_FINDMY HIGH`

**D3. Samsung SmartTag detection — HIGH confidence**
1. Place a Samsung SmartTag within 5 meters
2. **Expected:** Magenta blip labeled `SmartTag [HIGH]`
- Detection path: `companyId == SAMSUNG_COMPANY_ID (0x0075)` → `SAMSUNG_SMARTTAG HIGH`
- Note: Unlike Apple detection, no payload inspection needed; Samsung company ID is unambiguous

**D4. Tile detection via service UUID — HIGH confidence**
1. Bring a Tile tracker within range
2. **Expected:** Magenta blip labeled `Tile [HIGH]`
- Detection path (primary): `checkServiceUuids()` → UUID contains `0000feed` → `TILE HIGH`
- Detection path (secondary, if UUID not in ad): `companyId == TILE_COMPANY_ID (0x010D)` → `TILE HIGH`

**D5. Chipolo detection via service UUID — HIGH confidence**
1. Bring a Chipolo tracker within range
2. **Expected:** Magenta blip labeled `Chipolo [HIGH]`
- Detection path: `checkServiceUuids()` → UUID contains `0000fe33` → `CHIPOLO HIGH`

**D6. Anomaly accumulation cap**
1. Wave 5+ trackers near the phone
2. **Expected:** `allAnomalies` list caps at 60 entries (oldest pruned)
3. **Expected:** Ticker shows last 6 anomalies
4. No crash, no memory growth
- Code ref: `MainActivity.onScanUpdate()` → `allAnomalies.takeLast(60)`; `HudOverlayView.drawAnomalyTicker()` → `toShow = recentAnomalies.takeLast(6)`

**D7. No false positive on Apple Nearby Info**
1. Have an iPhone in BLE range advertising Apple Nearby (`type byte 0x10`)
2. **Expected:** iPhone does NOT appear as a tracker
3. **Expected:** No `[TRACKER]` anomaly for the iPhone
- Code ref: `TrackerDetector.checkApplePayload()` → `APPLE_NEARBY_TYPE (0x10)` → returns `null`

---

### Suite E — Baseline & Anomaly Engine

**E1. Set baseline**
1. Scan until stable (30+ seconds, W+B counts stable)
2. Tap **`SET BASELINE`**
3. **Expected:** Status shows `BASELINE SET — N devices`
4. **Expected:** Bottom HUD bar `BASE:N` matches
5. **Expected:** All current blips lose their YELLOW (NEW) color — already known
- Code ref: `SignalBaseline.setBaseline()` → saves all current devices; `analyze()` skips `baseline.isEmpty()` guard

**E2. NEW_DEVICE anomaly**
1. After baseline is set, introduce a new Wi-Fi AP or BLE device (e.g., hotspot from a second phone)
2. **Expected:** Yellow blip appears immediately on next scan delivery
3. **Expected:** Anomaly ticker shows `[NEW] <name> [WiFi]`
- Code ref: `SignalBaseline.analyze()` → `entry == null` → `NEW_DEVICE` anomaly

**E3. DEVICE_GONE with 3-miss debounce**
1. Set baseline with your phone's hotspot active
2. Turn off the hotspot
3. **Expected:** No GONE anomaly after 1st or 2nd miss — grey blip NOT shown yet
4. **Expected:** After 3rd miss (~96 seconds for Wi-Fi at 32s interval; ~6 seconds for BLE at 2s interval), GONE anomaly fires
5. **Expected:** Blip turns grey on radar
- Code ref: `SignalBaseline.analyze()` → `goneCandidates[id]` increments to ≥ 3 → `DEVICE_GONE` anomaly

**E4. RSSI_SPIKE anomaly**
1. Set baseline from across a room
2. Move the phone next to your router (RSSI change > 20 dBm)
3. **Expected:** Red blip on router's entry
4. **Expected:** Ticker shows `[RSSI+] RSSI +NNdBm (was -NN)`
- Code ref: `SignalBaseline.analyze()` → `rssiDelta > 20` → `RSSI_SPIKE`

**E5. CHANNEL_CHANGE anomaly**
1. Set baseline
2. On your router admin panel, change the Wi-Fi channel (e.g., ch 6 → ch 11 for 2.4G)
3. **Expected:** Ticker shows `[CH-SHIFT] CH 6->11`
- Code ref: `SignalBaseline.analyze()` → `device.channel != entry.channel` → `CHANNEL_CHANGE`
- Note: Channel 0 guards prevent false positives for BLE (always channel 0) and APs that don't report frequency

**E6. OPEN_NETWORK anomaly**
1. Create a hotspot with no password (open network) after baseline is set
2. **Expected:** Two anomalies fire: `[NEW]` + `[OPEN]`
3. **Expected:** Red blip for the new open network
- Code ref: `SignalBaseline.analyze()` → `entry == null && device.isOpen` → both `NEW_DEVICE` and `OPEN_NETWORK` anomalies
- `DetectedDevice.isOpen`: capabilities empty or equals `[ESS]`

**E7. ROGUE_AP detection**
1. Set baseline with your home router "HomeNetwork" (BSSID: AA:BB:CC:DD:EE:FF)
2. Create a hotspot named exactly "HomeNetwork" on a second phone (this simulates a rogue AP)
3. **Expected:** Ticker shows `[ROGUE-AP] SSID 'HomeNetwork' seen on new BSSID <mac>`
4. **Expected:** Red blip for rogue AP
- Code ref: `SignalBaseline.detectRogueAps()` — finds SSID match on different BSSID

**E8. Baseline persistence across restart**
1. Set baseline
2. Note baseline count
3. Force-stop the app and relaunch
4. **Expected:** `BASE:N` in HUD matches previous count immediately on launch
5. **Expected:** No permission re-prompt (if already granted)
- Code ref: `SignalBaseline.saveToPrefs()` + `loadFromPrefs()` via `SharedPreferences`

**E9. Clear baseline**
1. With a baseline active, tap **`CLEAR`**
2. **Expected:** Status shows `BASELINE CLEARED`
3. **Expected:** `BASE:0` in bottom HUD
4. **Expected:** No more anomaly events (except tracker detection, which is baseline-independent)
5. **Expected:** Yellow (NEW) blips disappear — all devices revert to green/cyan
- Code ref: `SignalBaseline.clear()` → `baseline.clear()`, `goneCandidates.clear()`, `prefs.edit().clear()`

---

### Suite F — UI / Rendering

**F1. Radar sweep visible**
- **Expected:** Green sweep line rotates 360° every ~4 seconds
- **Expected:** Trailing glow sector visible behind sweep line
- Code ref: `RadarView.animator` — 4000ms duration, `LinearInterpolator`

**F2. Concentric range rings**
- **Expected:** 4 grey rings visible, labeled `-30`, `-50`, `-70`, `-90` dBm from inside out
- Code ref: `RadarView.drawGrid()` + `drawRangeLabels()`

**F3. Blip color coding**
| Color | Meaning | Verify by |
|-------|---------|-----------|
| Green | Known Wi-Fi AP | Fresh scan, no baseline |
| Cyan | Known BLE device | Fresh scan, no baseline |
| Yellow | New device not in baseline | Set baseline, introduce new device |
| Red | Anomaly (RSSI spike, open net, rogue AP) | See E4/E6/E7 |
| Magenta | Known tracker | Bring AirTag/Tile within range |
| Grey | GONE (3-miss debounce) | Remove baselined device |

**F4. Pulse ring on flagged devices**
- **Expected:** Anomalous/tracker blips emit an expanding ring pulse (period ~1.2 seconds)
- **Expected:** Normal green/cyan blips have NO pulse ring
- Code ref: `RadarView.drawDeviceBlips()` → `if (flagColor != null && flagColor != cGrey)`

**F5. Label visibility rules**
- **Expected:** Device labels appear for devices with RSSI > -60 OR flagged
- **Expected:** Labels over 14 characters are truncated with `..`
- **Expected:** Labels NOT visible for weak, non-flagged devices (screen not cluttered)
- Code ref: `if (dev.rssi > -60 || flagColor != null)` + `it.take(14) + ".."`

**F6. HUD top bar**
- **Expected:** `[ SCANNING ]` in green when scan active
- **Expected:** `[ PAUSED ]` in yellow when PAUSE pressed
- **Expected:** `W:N B:N` totals top-right
- **Expected:** `TRK:N` in magenta and `ANOM:N` in red appear only when non-zero

**F7. Screen stay-on**
- **Expected:** Screen does NOT auto-lock while scanning
- Code ref: `MainActivity.onCreate()` → `window.addFlags(FLAG_KEEP_SCREEN_ON)`

**F8. Portrait lock**
- **Expected:** App does NOT rotate to landscape; stays portrait regardless of physical orientation
- Code ref: `AndroidManifest.xml` → `screenOrientation="portrait"`

---

### Suite G — Edge Cases & Error Handling

**G1. BLE unavailable (Bluetooth OFF)**
1. Turn Bluetooth OFF before launching
2. **Expected:** `SCANNING WiFi (BLE unavailable)` in status
3. **Expected:** No crash; Wi-Fi scanning proceeds normally
- Code ref: `BleScanner.isAvailable` → `adapter?.isEnabled == true`

**G2. Scan while Bluetooth toggled mid-session**
1. Start scanning (Wi-Fi + BLE)
2. Turn Bluetooth OFF
3. **Expected:** BLE scan fails silently; `onScanFailed` sets `running = false`
4. **Expected:** Wi-Fi scanning continues unaffected
5. **Expected:** No crash, no ANR
- Code ref: `BleScanner.scanCallback.onScanFailed()` + `SecurityException` catch in `start()`

**G3. Rapid PAUSE / SCAN toggle**
1. Tap SCAN (pause) and SCAN (resume) rapidly 5–6 times
2. **Expected:** No crash, no duplicate receivers registered, no duplicate BLE scans
- Code ref: `WifiScanner.stop()` → `try { unregisterReceiver } catch (_: IllegalArgumentException) {}`

**G4. App orientation config change** *(should not happen due to portrait lock, but test anyway)*
1. Force rotation via Developer Options → "Force allow apps to rotate"
2. Attempt to rotate
3. **Expected:** App remains portrait; manifest config lock overrides
4. If rotation happens anyway (Samsung override): `configChanges` includes `orientation|screenSize|screenLayout` so `onCreate` is NOT called → no scanner leak

---

## Known Limitations / Expected Deviations

| Observation | Not a bug | Reason |
|-------------|-----------|--------|
| Wi-Fi scan only updates every 32s after first 3 scans | ✅ | Android 10+ throttle enforcement; design intentional |
| BLE devices flicker (disappear/reappear) | ✅ | 15s expiry + MAC rotation; expected behavior |
| AirPods NOT detected as tracker | ✅ | Type byte `0x10` (Nearby) explicitly returns null; correct |
| Channel 0 for BLE devices | ✅ | BLE has no channel concept in this model |
| `wifiManager.startScan()` deprecation warning in logs | ✅ | Deprecated API, no replacement; suppress annotation present |
| Multiple RSSI_SPIKE anomalies for same device | ✅ | Each scan cycle re-evaluates; anomaly accumulates up to cap of 60 |
| Baseline not persisted on re-install | ✅ | SharedPreferences cleared on uninstall; by design |

---

## Samsung S24 / Android 14 Specific Gotchas

| Gotcha | Impact | Mitigation |
|--------|--------|------------|
| Location services must be SYSTEM-ON (not just permission) for Wi-Fi scans | Wi-Fi returns empty list | Enable via Quick Settings → Location |
| Samsung Battery Optimizer kills background scan callbacks | Missed Wi-Fi events | Set app to Unrestricted in Battery settings |
| BLE scan may throttle under Power Saving mode | Fewer BLE devices visible | Test in Balanced or Performance mode |
| "Wi-Fi scan throttling" in Developer Options | Prevents burst scans during development | Toggle off for faster dev testing |
| Samsung MAC randomization aggressive rotation | BLE devices appear/vanish repeatedly | Expected; not an app bug |
| One UI 6 "Scanning always available" setting | Wi-Fi scan accuracy | Enable via Settings → Location → Location services → Wi-Fi scanning |
| S24 may require "Nearby devices" permission group acknowledgment | BLE scan blocked on first launch | Permission dialog should cover this — confirm all three permissions granted |

---

## Pass/Fail Criteria

The build passes on-device verification if ALL of the following are true:

- [ ] **A1**: App launches, requests permissions, begins scanning on first run
- [ ] **B1**: At least 1 Wi-Fi AP blip visible within 10 seconds
- [ ] **C1**: At least 1 BLE blip visible within 6 seconds (with a BLE device nearby)
- [ ] **E1–E3**: Baseline set/clear/GONE-debounce all function correctly
- [ ] **D1** OR **D3** OR **D4**: At least one tracker type detected if hardware available
- [ ] **F1–F3**: Radar renders, sweeps, and color-codes blips correctly
- [ ] **G1**: Bluetooth-off case handled gracefully (no crash)
- [ ] No ANR (Application Not Responding) dialogs during any test
- [ ] No crash (SIGSEGV, IllegalStateException, NullPointerException visible in logs) during any test

---

## Log Collection During Testing

To capture logs while running tests (useful if something fails):

```bash
# Before launching the app:
adb logcat -c  # clear buffer

# While testing:
adb logcat -s ReconRadar:V AndroidRuntime:E ActivityManager:W

# If app crashes, capture the full crash stack:
adb logcat | grep -A 20 "FATAL EXCEPTION"
```

Key log tags to watch:
- `WifiManager` — scan results or throttle messages
- `BluetoothLeScanner` — scan start/stop events
- `ActivityThread` — ANR or lifecycle errors

---

*Generated from static analysis of commit HEAD on main branch (issue #10 + #11 merged). All test steps trace directly to source code in `app/src/main/java/com/reconradar/app/`.*
