# recon-radar — Claude Code Brief

Wi-Fi + BLE signal intelligence radar for Android. Scans nearby access points and Bluetooth Low Energy devices, visualizes them on a real-time tactical radar, detects known trackers (AirTag, Tile, SmartTag, Chipolo), and flags anomalies—all offline, no internet, no cloud.

**Status:** 🟢 Active — Phone-only radar UI, XReal AR removed, sideloadable debug APK via GitHub Actions CI.

## Product Vision

Build a side-loadable APK for Samsung S24 (Android 14+) that:
- Scans Wi-Fi access points and BLE devices in real time
- Visualizes them on a tactical radar display with color-coded threat/status indicators
- Detects known wireless trackers by manufacturer data, service UUIDs, and device name patterns
- Baselines the environment and flags anomalies (new devices, disappeared signals, RSSI spikes, channel hops, open networks)
- Runs fully offline—zero internet permissions, zero cloud dependency

**Platform:** Android (minSdk 29 / targetSdk 35, Kotlin)  
**Target device:** Samsung Galaxy S24 (Android 14+)

## Tech Stack

| Layer | Choice | Notes |
|-------|--------|-------|
| Language | Kotlin | JDK 17+ |
| UI | Canvas-based custom `View`s | Not Jetpack Compose—raw Canvas for radar performance |
| Build | Gradle 8.x (Kotlin DSL) | `app/build.gradle.kts` |
| Testing | JUnit 4 (JVM only) | `TrackerDetector` is pure Kotlin, no Android dependencies—tests run fast without emulator |
| Baseline storage | `SharedPreferences` | Key-indexed flat storage; minimal dependency footprint for a sideloadable APK |
| Permissions | 5 total | `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION` (required by Android for Wi-Fi), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` |

## Project Structure

**Real paths from README.md and history.md:**

```
recon-radar/
├── README.md              — Full feature overview, permissions table, architecture diagram,
│                            build instructions, tracker detection table, color code legend
├── history.md             — Project decisions, technical details (API levels, changelog),
│                            status, unfinished work
└── [Android project root below — actual paths in codebase]
    └── com/reconradar/app/
        ├── model/
        │   ├── DetectedDevice.kt    — Unified Wi-Fi/BLE device model
        │   └── Anomaly.kt           — Anomaly types and priority levels
        ├── scanner/
        │   ├── WifiScanner.kt       — Wi-Fi scan lifecycle + throttle management
        │   ├── BleScanner.kt        — BLE advertisement scanner with batched delivery
        │   ├── TrackerDetector.kt   — AirTag/Tile/SmartTag/Chipolo pattern matching
        │   └── SignalBaseline.kt    — Baseline storage + deviation analysis
        ├── hud/
        │   ├── RadarView.kt         — Canvas radar with sweep, dual-color blips
        │   ├── HudOverlayView.kt    — CRT overlay, stats, anomaly ticker
        │   └── [XRealPresentation.kt removed — see git history if needed again]
        └── MainActivity.kt           — Permissions, lifecycle, scan orchestration
```

## Current Status

🟢 **Active** — Phone-only radar display. XReal AR support removed (issue #2). GitHub Actions CI produces a sideloadable debug APK on every push to `main`. Android 14 compatibility verified.

### Recent Work
- Quality polish (issue #1): MIT LICENSE, `.gitignore`, JVM unit tests, history.md
- BLE company ID fix (issue #9): SmartTag/Tile HIGH-confidence detection via manufacturer data
- XReal AR path removed: App now renders exclusively to phone screen

### Immediate Next Steps
- On-device verification on Samsung S24 (Android 14)—Wi-Fi scan, BLE scan, tracker detection end-to-end

## Key Decisions & Architecture

### Phone-Only Radar (XReal Removed)
The app originally supported XReal One Pro glasses via the `Presentation` API (external USB-C display). That code path is documented in git history but removed from the current codebase per issue #2. App now renders exclusively to the phone screen via Canvas-based custom `View`s.

### Single-Activity Architecture
`MainActivity` owns all permission lifecycle, scanner orchestration, and UI state. No fragments, no Jetpack Compose. Keeps the sideloadable APK footprint minimal.

### Offline-First, Zero Permissions Creep
- No internet, camera, microphone, storage, or contacts permissions
- Wi-Fi + BLE + fine location only (location required by Android API 29+ for Wi-Fi scan results)
- All functionality uses stock Android APIs—no root required

### Canvas-Based Radar Display (Not Compose)
Raw `Canvas` rendering for performance. The radar sweep, blip colors, and overlay text all draw directly on Canvas. Jetpack Compose would add unnecessary dependencies for a sideloadable APK.

### TrackerDetector as Pure Kotlin Object
No Android dependencies in `TrackerDetector.kt`. This enables fast JVM unit tests without a device or emulator. Test coverage: all detection layers (service UUID match, manufacturer data pattern, device name pattern).

### SignalBaseline with SharedPreferences
Simple key-indexed flat storage for detected devices and baseline state. Avoids Room/SQLite dependency overhead. Persists across app restarts.

### Radar Color Code
- **Green**: Known Wi-Fi AP
- **Cyan**: BLE device
- **Yellow**: NEW device (not in baseline)
- **Red**: Anomaly (RSSI spike, open network, rogue AP)
- **Magenta**: Known tracker (AirTag, Tile, SmartTag, Chipolo)
- **Grey**: Device disappeared from baseline (3-miss debounce)

### Tracker Detection Strategy (Layered, Priority-Ordered)
1. **Service UUID match** (HIGH confidence) — Tile `0000feed-*`, Chipolo `0000fe33-*`
2. **Manufacturer data pattern** (HIGH confidence) — Apple type byte `0x12`, AirTag-sized payload heuristic (25–30 bytes)
3. **Device name pattern** (MEDIUM/LOW confidence) — fallback for devices that rotate MACs

### Anomaly Debouncing
"Device gone" anomalies require 3 consecutive scan misses before firing—prevents false positives from intermittent BLE advertisements or Wi-Fi scan throttling on Android 10+.

## Agent Notes

### Testing
- **JVM unit tests, no emulator needed**: `TrackerDetector` is pure Kotlin. Run `./gradlew test` for fast feedback on detection logic.
- **Integration tests**: Wi-Fi and BLE scanners require Android device/emulator. Manual testing on Samsung S24 is the current QA path.

### Why Not iOS
iOS blocks Wi-Fi scanning entirely—there is no public API to enumerate nearby networks, SSIDs, or signal strengths. Android is the only viable platform for the full feature set.

### Build & Deploy
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
GitHub Actions CI auto-produces a sideloadable debug APK on push to `main`.

### XReal Support (Historical Context)
The `Presentation` API approach for external USB-C display output is documented in git history. If glasses support is ever re-added, reference that prior implementation. For now, phone-screen-only is the target.

### Permissions & Android Version Quirks
- `ACCESS_FINE_LOCATION` is required by Android 29+ to receive Wi-Fi scan results, even though the app doesn't directly use GPS
- BLE scanning on Android 12+ requires both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions with appropriate receiver flags
- Wi-Fi scan throttling on Android 10+ can hide devices for seconds—anomaly debouncing accounts for this

### Dependency Footprint
Minimal intentionally for sideloadable APK: Kotlin stdlib, Android framework, JUnit 4 for tests. No Jetpack Compose, Room, Retrofit, or other heavy libraries.