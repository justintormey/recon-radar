# Recon Radar — Project History

## Project Overview

Wi-Fi + BLE signal intelligence radar for Android (Samsung S24). Scans nearby access points and Bluetooth Low Energy devices, visualizes them on a real-time tactical radar, detects known trackers (AirTag, Tile, SmartTag, Chipolo), and flags environmental anomalies — all offline with no cloud dependency.

**Platform:** Android (minSdk 29 / targetSdk 35)  
**Language:** Kotlin  
**Key APK target:** Samsung Galaxy S24 (Android 14+)

---

## Key Context & Decisions

### Architecture
- **Pure Android Presentation API for XReal glasses** — no XREAL SDK, no Unity, no ControlGlasses app required. XReal One Pro registers as an external display via USB-C; the app renders via the standard `Presentation` class.
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

🟡 **in-progress** — Core functionality complete; side-loadable APK buildable. XReal HUD support present but not the primary focus per product direction (Justin no longer has XReal glasses).

---

## Unfinished Work

### Immediate Next Steps
- Remove/disable XReal AR view (per product vision — focus on phone-only radar)
- End-to-end test build on Samsung S24 (Android 14)
- Verify Wi-Fi scan throttle handling (Android 10+ limits scans to ~4/2 min)

### Future Enhancements
- Export scan log to local file (no cloud)
- Historical baseline comparison across sessions
- Persistent device naming / notes
- RSSI smoothing (rolling average to reduce jitter on radar blips)

---

## Important Notes

- **iOS is not viable** for this tool — iOS blocks Wi-Fi enumeration entirely. Android is the only platform with full public API access.
- **Root not required** — all functionality uses stock Android APIs.
- **XReal One Pro compatibility** uses standard Android display output, not a vendor SDK. Any USB-C display-out capable phone should work.

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
