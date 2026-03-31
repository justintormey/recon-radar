# RECON RADAR

Wi-Fi + BLE signal intelligence radar with XReal One Pro HUD support.

Scans nearby Wi-Fi access points and Bluetooth Low Energy devices, visualizes them
on a real-time tactical radar display, detects known trackers (AirTag, Tile, SmartTag),
and flags anomalies — new devices, disappeared signals, RSSI spikes, channel changes, and
open networks. Runs fully offline. No camera, no mic, no internet, no cloud. Just signal.

Inspired by [SØPHIA CIVOPS](https://detecxceo.gumroad.com/l/qospds).

## Why Not iPhone

iOS blocks Wi-Fi scanning entirely — there is no public API to enumerate nearby networks,
SSIDs, or signal strengths. CoreBluetooth supports BLE scanning, but Wi-Fi recon is core
to this tool. Android is the only viable platform for the full feature set.

## Features

| Feature | Detail |
|---------|--------|
| Wi-Fi radar | Scans all nearby APs via `WifiManager`, plots on tactical radar |
| BLE radar | Scans all BLE advertisements, plots alongside Wi-Fi in cyan |
| Tracker detection | Identifies AirTag, Apple Find My, Tile, Samsung SmartTag, Chipolo by manufacturer data, service UUIDs, and name patterns |
| Anomaly detection | Baselines your environment, flags NEW devices, GONE signals, RSSI spikes (>20dBm), channel hops, open networks |
| XReal One Pro | Full HUD renders onto glasses via Android Presentation API — no SDK, no Unity |
| Offline-first | Zero internet permissions, zero network calls |
| No root | Stock Android APIs only |

## Radar Color Code

| Color | Meaning |
|-------|---------|
| Green | Known Wi-Fi AP |
| Cyan | BLE device |
| Yellow | New device (not in baseline) |
| Red | Anomaly (RSSI spike, open network, rogue AP) |
| Magenta | Known tracker (AirTag, Tile, SmartTag, etc.) |
| Grey | Device that disappeared from baseline |

## Tracker Detection

The `TrackerDetector` identifies known wireless tracker patterns:

| Tracker | Detection Method |
|---------|-----------------|
| Apple AirTag / Find My | Manufacturer data type byte `0x12`, payload size heuristic |
| Tile | Service UUID `0000feed-*`, device name prefix |
| Samsung SmartTag | Company ID, device name pattern |
| Chipolo | Service UUID `0000fe33-*` |
| Generic beacons | iBeacon / Eddystone name patterns |

## XReal One Pro Support

XReal One Pro glasses register as an external display via USB-C. The app:

1. Auto-detects glasses connect/disconnect via `DisplayManager`
2. Renders full radar + HUD overlay at native resolution via `Presentation` API
3. Keeps the glasses display awake during scanning
4. Auto-reconnects on activity resume

No XREAL SDK, Unity, or ControlGlasses app required. Works with any phone that supports
USB-C display output.

## Build

Requirements:
- Android Studio Ladybug (2024.2+) or command-line Android SDK
- JDK 17+
- Android SDK API 35

```bash
cd xreal-recon-radar
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Why |
|-----------|-----|
| `ACCESS_WIFI_STATE` | Read Wi-Fi scan results |
| `CHANGE_WIFI_STATE` | Trigger Wi-Fi scans |
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi scan results (API 29+) |
| `BLUETOOTH_SCAN` | BLE device scanning (Android 12+) |
| `BLUETOOTH_CONNECT` | Read BLE device names (Android 12+) |

No internet. No camera. No microphone. No storage. No contacts.

## Architecture

```
com.reconradar.app/
├── model/
│   ├── DetectedDevice.kt     — unified Wi-Fi/BLE device model
│   └── Anomaly.kt            — anomaly types and priority levels
├── scanner/
│   ├── WifiScanner.kt        — Wi-Fi scan lifecycle + throttle management
│   ├── BleScanner.kt         — BLE advertisement scanner with batched delivery
│   ├── TrackerDetector.kt    — AirTag/Tile/SmartTag pattern matching
│   └── SignalBaseline.kt     — baseline storage + deviation analysis
├── hud/
│   ├── RadarView.kt          — Canvas radar with sweep, dual-color blips
│   ├── HudOverlayView.kt     — CRT overlay, stats, anomaly ticker
│   └── XRealPresentation.kt  — external display rendering for glasses
└── MainActivity.kt            — permissions, lifecycle, scan orchestration
```
