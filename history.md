# Recon Radar — Project History

## Project Overview

Wi-Fi + BLE signal intelligence radar for Android (Samsung S24). Scans nearby access points and Bluetooth Low Energy devices, visualizes them on a real-time tactical radar, detects known trackers (AirTag, Tile, SmartTag, Chipolo), and flags environmental anomalies — all offline with no cloud dependency.

**Platform:** Android (minSdk 29 / targetSdk 35)  
**Language:** Kotlin  
**Key APK target:** Samsung Galaxy S24 (Android 14+)

---

## Key Context & Decisions

### Architecture
- **Phone-only radar UI** — XReal AR display path removed (issue #2). App renders exclusively to the phone screen.
- **Single-activity design** — `MainActivity` owns all permission lifecycle and orchestrates scanners. No fragments, no Jetpack Compose (Canvas-based custom `View`s for radar performance).
- **Offline-first, zero permissions creep** — no internet, camera, mic, or storage permissions. Wi-Fi + BLE + fine location only.
- **`TrackerDetector` as a pure Kotlin object** — no Android dependencies, enabling fast JVM unit tests without a device or emulator.
- **`SignalBaseline` persists via `SharedPreferences`** — simple key-indexed flat storage rather than Room/SQLite to keep dependencies minimal for a side-loadable APK.

### Radar Display
- Green = known Wi-Fi AP
- Cyan = BLE device
- Yellow = NEW (not in baseline)
- Red = Anomaly (RSSI spike, open network, rogue AP)
- Magenta = Known tracker
- Grey = GONE (3-miss debounce)

### Tracker Detection Strategy
Detection uses three layers in priority order:
1. **Service UUID match** (HIGH confidence) — Tile `0000feed-*`, Chipolo `0000fe33-*`
2. **Manufacturer data pattern** (HIGH confidence) — Apple type byte `0x12`; AirTag-sized payload heuristic (25–30 bytes)
3. **Device name pattern** (MEDIUM/LOW confidence) — fallback for devices that rotate MACs

### Anomaly Debouncing
"Device gone" anomalies require 3 consecutive scan misses before firing. This prevents false positives from intermittent BLE advertisements or Wi-Fi scan throttling on Android 10+.

---

## Current Status

🟢 **active** — Phone-only radar UI. XReal AR removed. GitHub Actions CI produces a sideloadable debug APK on every push to `main`. Android 14 compatibility verified (permissions, receiver flags, BLE security guards all in place).

---

## Unfinished Work

### Immediate Next Steps
- On-device verification on Samsung S24 (Android 14) — Wi-Fi scan, BLE scan, tracker detection

### Future Enhancements
- Export scan log to local file (no cloud)
- Historical baseline comparison across sessions
- Persistent device naming / notes
- RSSI smoothing (rolling average to reduce jitter on radar blips)

---

## Important Notes

- **iOS is not viable** for this tool — iOS blocks Wi-Fi enumeration entirely. Android is the only platform with full public API access.
- **Root not required** — all functionality uses stock Android APIs.
- **XReal support removed** — the `Presentation` API approach is documented in git history if ever needed again, but `XRealPresentation.kt` is gone from the codebase.

---

## Technical Details

| Item | Value |
|------|-------|
| `compileSdk` | 35 |
| `minSdk` | 29 (Android 10) |
| `versionName` | 1.0.0 |
| Build tool | Gradle 8.x with Kotlin DSL |
| JDK | 17 |
| Test framework | JUnit 4 (JVM unit tests, no emulator needed) |

### Changelog

**2024 — Initial build**
- Wi-Fi + BLE scanning, tactical radar display, tracker detection, anomaly baseline engine, XReal One Pro HUD via Presentation API

**2026-04-13 — Quality polish (issue #1)**
- Added MIT LICENSE
- Added `.gitignore` (Android standard + keystore protection)
- Added JVM unit tests: `DetectedDeviceTest` (channel, band, open-network, radar math) and `TrackerDetectorTest` (all detection layers)
- Added `history.md` (this file)
- Added `testImplementation` dependencies to `app/build.gradle.kts`

**2026-04-18 — BLE company ID fix: SmartTag/Tile HIGH-confidence detection (issue #9)**
- `BleScanner.extractManufacturerData` now returns `Pair<Int, ByteArray>?` (company ID + payload) instead of discarding the SparseArray key
- Added `manufacturerCompanyId: Int?` field to `DetectedDevice`
- `TrackerDetector.checkManufacturerData` dispatches on company ID first: Samsung `0x0075` → SmartTag HIGH, Tile `0x010D` → Tile HIGH, Apple `0x004C` → `checkApplePayload()` for AirTag vs FindMy distinction
- Extracted `checkApplePayload()` helper; legacy path (no company ID, e.g. tests with raw bytes) falls through to it unchanged
- Added 4 new unit tests covering Samsung/Tile/Apple company ID paths and unknown-company-ID null return

**2026-04-17 — XReal removal + sideloadable APK pipeline (issue #2)**
- Deleted `XRealPresentation.kt` entirely
- Stripped all XReal state/methods from `MainActivity` (display listener, attach/detach, mirroring calls)
- Removed `xrealConnected` from `HudOverlayView`; cleaned up HUD top-bar to phone-only layout
- Verified Android 14 compatibility: `RECEIVER_NOT_EXPORTED`, BLE permission guards, scan throttle pacing all correct
- Added `.github/workflows/build-apk.yml` — CI produces `app-debug.apk` artifact on every push to `main`

**2026-04-18 — Implement ROGUE_AP detection (issue #11)**
- `SignalBaseline.analyze()` now generates `Anomaly.Type.ROGUE_AP` when a live Wi-Fi device presents a BSSID not in the baseline but an SSID that matches a known baseline entry
- Detection logic extracted to `SignalBaseline.detectRogueAps()` companion function (pure Kotlin, no Android deps) enabling JVM unit testing without Context
- Added `SignalBaselineTest` with 8 unit tests covering: positive detection, known BSSID suppression, different SSID no-trigger, BLE exclusion, blank SSID guard, empty baseline, empty device list, multi-rogue scan, and priority validation
- `ROGUE_AP` is no longer dead code; enum variant is fully exercised

**2026-04-18 — Add Settings deep link when Location services are OFF (issue #22)**
- `beginScanning()` now shows an `AlertDialog` (instead of a silent Toast) when `LocationManager.isLocationEnabled` is false
- Dialog has "OPEN SETTINGS" button that launches `Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)` — sends user directly to the system Location toggle
- Added `locationSettingsLaunched: Boolean` flag; `onResume()` checks it on return and auto-retries `requestPermissionsAndStart()` if Location is now enabled — zero extra taps required
- Added 4 string resources: `location_off_dialog_title`, `location_off_dialog_message`, `location_off_open_settings`, `location_off_dismiss`
- Semver: PATCH (no new API surface, purely UX improvement to existing guard)

**2026-04-18 — Warn user when system Location services are OFF (issue #13)**
- `MainActivity.beginScanning()` now checks `LocationManager.isLocationEnabled` before starting any scanner
- If Location toggle is OFF, sets status text to "Enable Location services for Wi-Fi scan" and shows a matching Toast; returns early without starting WifiScanner/BleScanner
- Added `location_services_off` string resource to `strings.xml`
- Fixes silent `W:0` HUD with no explanation on Samsung One UI / Android 14 when Location is disabled

**2026-04-18 — BLE MAC randomization false-positive suppression (issue #12)**
- `SignalBaseline.BaselineEntry` gains `capabilities: String = ""` field (BLE service UUIDs stored at baseline-set time; default empty for backward compat with existing SharedPreferences data)
- `setBaseline()` now persists `capabilities`; `saveToPrefs`/`loadFromPrefs` serialize/restore `${i}_caps`
- `analyze()` builds a secondary `bleStableIndex: Map<String, BaselineEntry>` from BLE entries with stable composite keys, rebuilt each cycle
- Two suppression mechanisms implemented:
  1. **Tracker suppression** — if `KNOWN_TRACKER` fires, `NEW_DEVICE` is skipped (reduces noise for AirTag/SmartTag/Tile with rotating MACs)
  2. **Composite-key re-match** — BLE device lookup falls back to `name|serviceUUIDs` key; rotating MAC on a named device (Galaxy Buds, Wear OS watch) re-matches baseline without generating NEW_DEVICE
- New companion function `bleStableKey(name, capabilities)` — pure, testable without Context; returns non-null only when both name and service UUIDs are non-empty/non-placeholder
- Added 8 new unit tests covering: stable key non-null, null-for-unnamed, null-for-empty-caps, composite key symmetry, BLE never triggers rogue AP, stable key requires both name+caps
- Tracker detection NOT affected — operates on manufacturer data + service UUIDs, not MAC

**2026-04-18 — Fix DEVICE_GONE false alarms on BLE MAC rotation (issue #17)**
- `SignalBaseline.analyze()` previously keyed `goneCandidates` off raw MAC and never consulted the `bleStableIndex` composite re-match in the DEVICE_GONE debounce loop
- After MAC rotation, old MAC accumulated 3 misses and fired spurious `DEVICE_GONE` even though the physical device was still present under its new MAC
- Fix: added `compositeMatchedBaselineIds` set populated during the device loop whenever a baseline entry is matched via stable composite key (not primary MAC); union it into `effectiveCurrentIds` before the DEVICE_GONE loop
- Extracted `compositeMatchedIds()` as a pure companion function (no Android deps) enabling JVM unit tests
- Added 4 new JVM unit tests: rotated MAC → old baseline ID returned; unnamed device → empty set; primary MAC match skipped; effectiveCurrentIds union prevents false alarm

**2026-04-18 — Fix DEVICE_GONE grey blips not rendered on radar (issue #16)**
- `RadarView` gains a `goneDevices: List<DetectedDevice>` field populated inside `updateDevices()` from `DEVICE_GONE` anomalies
- Ghost stubs are de-duplicated by device ID (accumulation buffer can hold many repeated GONE anomalies for the same device)
- `drawDeviceBlips()` now iterates `devices + goneDevices` so ghost positions are plotted even though the device is absent from the live scan list
- Existing grey rendering path (no pulse ring, static dim dot) works unchanged since `flaggedIds[id] == cGrey` for all gone devices
- Zero API surface change — `MainActivity.onScanUpdate()` call site is unaffected

**2026-04-18 — Fix missing Gradle wrapper files (issue #10)**
- Added `gradlew` shell script (Gradle 8.11.1, sourced from official Gradle v8.11.1 GitHub tag)
- Added `gradle/wrapper/gradle-wrapper.jar` binary (43 KB bootstrap jar, same version)
- CI was broken: `chmod +x gradlew` and `./gradlew assembleDebug` both failed with file-not-found
- Pipeline now produces sideloadable debug APKs again; unblocks on-device verification (issue #6)
