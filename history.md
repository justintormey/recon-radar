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

**2026-04-17 — XReal removal + sideloadable APK pipeline (issue #2)**
- Deleted `XRealPresentation.kt` entirely
- Stripped all XReal state/methods from `MainActivity` (display listener, attach/detach, mirroring calls)
- Removed `xrealConnected` from `HudOverlayView`; cleaned up HUD top-bar to phone-only layout
- Verified Android 14 compatibility: `RECEIVER_NOT_EXPORTED`, BLE permission guards, scan throttle pacing all correct
- Added `.github/workflows/build-apk.yml` — CI produces `app-debug.apk` artifact on every push to `main`

**2026-04-18 — Fix missing Gradle wrapper files (issue #10)**
- Added `gradlew` shell script (Gradle 8.11.1, sourced from official Gradle v8.11.1 GitHub tag)
- Added `gradle/wrapper/gradle-wrapper.jar` binary (43 KB bootstrap jar, same version)
- CI was broken: `chmod +x gradlew` and `./gradlew assembleDebug` both failed with file-not-found
- Pipeline now produces sideloadable debug APKs again; unblocks on-device verification (issue #6)
