# On-Device Verification Research — Issue #6
## Samsung S24 Wi-Fi + BLE Scanning

**Date:** 2026-04-18  
**Pipeline stage:** Research  
**Analyst:** research-analyst agent  
**Method:** Static code audit (physical device not available to headless agent)

---

## Methodology

Full read of all 9 Kotlin source files + manifest + layout + CI workflow + 3 test files.
Every test step in the existing `on-device-verification-guide.md` was traced to its code path.
Findings are classified as: CRITICAL (test fails), MEDIUM (behavioral deviation from guide), LOW (quality/clarity), INFO (tester calibration note).

---

## Findings

### CRITICAL — None

No code paths that would produce a crash, ANR, or outright failure of the minimum pass criteria (A1, B1, C1, E1–E3, F1–F3, G1).

---

### MEDIUM — Behavioral Deviations from Documentation

#### M1: Grey "GONE" Blips Never Rendered on Radar

**Affected guide tests:** C2, E3, F3 (grey blip row in color table)

**What the guide says:** When a baselined device disappears after 3 scan misses, "Blip turns grey on radar."

**What actually happens:** Grey blips cannot appear. `RadarView.updateDevices(all, allAnomalies)` sets:
```kotlin
this.devices = detected   // live devices only
```
The `DEVICE_GONE` anomaly creates a synthetic `DetectedDevice` in `allAnomalies` and its ID is added to `flaggedIds` with `cGrey`. But `drawDeviceBlips()` only iterates over `this.devices` — the gone device is never in that list, so the grey flag is dead code in practice.

**Visible effect:** The GONE anomaly DOES fire in the ticker (`[GONE] <name> disappeared`) and the ANOM counter increments. The radar just never shows a grey blip. The ticker + status behavior matches the guide; the visual radar element does not.

**Fix path:** Either add gone-device stubs to the rendered device list, or explicitly draw grey ghost blips by iterating `allAnomalies` in `drawDeviceBlips`. Scope: ~15 lines in `RadarView.kt`.

**Semver:** PATCH

---

#### M2: Button Labels in Guide Don't Match UI

**Affected guide tests:** E1, E8, E9

**What the guide says:** "Tap **`SET BASELINE`**" and "tap **`CLEAR`**"

**Actual button labels:** `SET BASE` (from `strings.xml: btn_baseline`) and `CLR BASE` (from `strings.xml: btn_clear`)

**Impact:** Tester confusion only. Status text matches guide (`"BASELINE SET — N devices"`, `"BASELINE CLEARED"`). Not a code bug.

**Fix path:** Update guide text to match actual labels: "Tap **`SET BASE`**" and "Tap **`CLR BASE`**". 

---

### LOW — Code Quality

#### L1: Mutable Shared Paint Objects in Anomaly Ticker

**Location:** `HudOverlayView.drawAnomalyTicker()` lines 108–113

```kotlin
val paint = when {
    a.type == Anomaly.Type.KNOWN_TRACKER -> textAlert.apply { color = cMagenta }
    a.isHighPriority -> textAlert.apply { color = cRed }
    a.type == Anomaly.Type.NEW_DEVICE -> textAlert.apply { color = cYellow }
    else -> textDim.apply { color = cGreenDim }
}
```

`textAlert` and `textDim` are shared Paint objects. Each `.apply { color = X }` mutates state before returning the reference. This works correctly today (color is set immediately before `drawText`), but any future code inserted between the `when` expression and `drawText` could corrupt colors for mixed-type anomaly lists.

**Impact:** None currently. Fragile pattern.

**Fix:** Create per-call paint instances via `Paint(textAlert).apply { color = X }` or pre-allocate one paint per color. Scope: ~8 lines.

---

### INFO — Tester Calibration Notes

#### I1: Wi-Fi Scan Throttling on Samsung S24

Android 10+ limits to 4 scans per 2 minutes in foreground. `WifiScanner` paces: 3 bursts at 5s, then 32s intervals. For faster development testing, disable via:
`Settings → Developer Options → Wi-Fi scan throttling → OFF`

This does NOT affect pass/fail — throttled behavior is by design.

#### I2: AirTag Detection Relies on Payload Size Heuristic

`checkApplePayload()` classifies AirTag vs. generic FindMy by payload size (`data.size in 25..30`). Standard AirTag firmware sends a 25-byte payload after company ID. If Apple changes the advertisement format in a firmware update, this could produce false APPLE_FINDMY instead of AIRTAG. Detection still fires (still HIGH confidence tracker); only the type label is affected.

#### I3: Galaxy Buds MAC Rotation — Composite-Key Re-match Scope

`bleStableKey()` only re-matches when BOTH device name AND service UUID string are non-empty. Devices that randomize MAC AND don't advertise service UUIDs (e.g., random third-party earbuds) will generate `NEW_DEVICE` anomalies on each MAC rotation. This is documented behavior, not a bug.

#### I4: AirTags Always Generate KNOWN_TRACKER, Even After Baseline

Tracker detection is baseline-independent (runs before the baseline check). An AirTag will generate a KNOWN_TRACKER anomaly on every scan cycle regardless of baseline state. The `NEW_DEVICE` suppression logic (when tracker fires, skip NEW_DEVICE) is correct — the ticker will show `[TRACKER] AirTag [HIGH]`, not `[NEW]`.

#### I5: "Wi-Fi Scanning Always Available" Samsung Setting

For accurate Wi-Fi results even when the Wi-Fi radio is off (for passive monitoring), enable:
`Settings → Location → Location services → Wi-Fi scanning`

If this is OFF and Wi-Fi radio is OFF, `wifiDevices` will be empty. If Wi-Fi radio is ON, this setting doesn't matter.

#### I6: CI APK Status

GitHub Actions workflow (`build-apk.yml`) is in place. The last commit pushed is `78a9a44` (BLE MAC randomization fix). The APK artifact should be available at:
`https://github.com/justintormey/recon-radar/actions`

CI was previously broken due to missing Gradle wrapper (fixed in #10). Wrapper is now present at `gradlew` and `gradle/wrapper/gradle-wrapper.jar`.

---

## Code Path Verification Table

| Guide Test | Code Path | Status |
|-----------|-----------|--------|
| A1 permission dialog | `requestPermissionsAndStart()` → `permissionLauncher` | ✅ Correct |
| A2 location denied | `permissionLauncher` result → `statusText.text = "LOCATION DENIED…"` | ✅ Correct |
| A3 BLE-only denial | `beginScanning(bleAvailable=false)` → `"SCANNING WiFi (BLE unavailable)"` | ✅ Correct |
| A4 re-launch | `needed.isEmpty()` → `beginScanning(bleAvailable=true)` | ✅ Correct |
| B1 AP blips | `WifiScanner.deliverResults()` → `DetectedDevice(type=WIFI)` | ✅ Correct |
| B2 throttle cadence | `scheduleNext()` → `if (scansSinceReset < 3) 5_000L else 32_000L` | ✅ Correct |
| B3 hidden SSID | `sr.SSID.ifEmpty { "<hidden>" }` | ✅ Correct |
| B4 band counts | `DetectedDevice.wifiBand` → `frequency < 3000 → "2.4G"` | ✅ Correct |
| B5 RSSI distance | `DetectedDevice.radarDistance` log-distance formula | ✅ Correct |
| C1 BLE blips | `BleScanner.processResult()` + 2s delivery | ✅ Correct |
| C2 BLE 15s expiry | `deviceMap.entries.removeAll { it.value.timestamp < cutoff }` | ✅ Correct, but grey blip won't show (M1) |
| C3 unnamed BLE | `name.ifEmpty { "<unnamed>" }` | ✅ Correct |
| C4 low-latency | `SCAN_MODE_LOW_LATENCY` + `setReportDelay(0)` | ✅ Correct |
| D1 AirTag HIGH | `companyId == 0x004C` → `checkApplePayload()` → `data[0] == 0x12` + size 25–30 | ✅ Correct |
| D2 FindMy non-AirTag | Same path, payload size NOT in 25..30 → APPLE_FINDMY | ✅ Correct |
| D3 SmartTag HIGH | `companyId == 0x0075` → SAMSUNG_SMARTTAG HIGH | ✅ Correct |
| D4 Tile service UUID | `TILE_SERVICE_UUID in caps` → TILE HIGH | ✅ Correct |
| D5 Chipolo | `CHIPOLO_SERVICE_UUID in caps` → CHIPOLO HIGH | ✅ Correct |
| D6 anomaly cap 60 | `allAnomalies.takeLast(60)` | ✅ Correct |
| D7 no AirPods tracker | `APPLE_NEARBY_TYPE (0x10)` → returns null | ✅ Correct |
| E1 set baseline | `SignalBaseline.setBaseline()` + `BASE:N` HUD | ✅ Correct (button label discrepancy: M2) |
| E2 NEW_DEVICE | `entry == null` → NEW_DEVICE anomaly | ✅ Correct |
| E3 GONE 3-miss | `goneCandidates[id] >= 3` → DEVICE_GONE | ✅ Ticker correct, grey blip missing (M1) |
| E4 RSSI_SPIKE | `rssiDelta > 20` | ✅ Correct |
| E5 CHANNEL_CHANGE | `device.channel != entry.channel && entry.channel != 0 && device.channel != 0` | ✅ Correct |
| E6 OPEN_NETWORK | `entry == null && device.isOpen` → both NEW_DEVICE + OPEN_NETWORK | ✅ Correct |
| E7 ROGUE_AP | `detectRogueAps()` companion function | ✅ Correct |
| E8 baseline persistence | `saveToPrefs()` / `loadFromPrefs()` SharedPreferences | ✅ Correct |
| E9 clear baseline | `baseline.clear()`, `goneCandidates.clear()`, `prefs.edit().clear()` | ✅ Correct (button label: M2) |
| F1 radar sweep | `ValueAnimator` 4000ms LinearInterpolator | ✅ Correct |
| F2 range rings | `drawGrid()` 4 rings + `drawRangeLabels()` -30/-50/-70/-90 | ✅ Correct |
| F3 blip colors | `flaggedIds` dispatch in `drawDeviceBlips()` | ✅ Green/cyan/yellow/red/magenta correct; grey never renders (M1) |
| F4 pulse ring | `flagColor != null && flagColor != cGrey` guard | ✅ Correct |
| F5 label rules | `rssi > -60 || flagColor != null` + 14-char truncation | ✅ Correct |
| F6 HUD top bar | `drawTopBar()` | ✅ Correct |
| F7 screen stay-on | `FLAG_KEEP_SCREEN_ON` in `onCreate()` | ✅ Correct |
| F8 portrait lock | `screenOrientation="portrait"` in manifest | ✅ Correct |
| G1 BT off | `BleScanner.isAvailable` guard | ✅ Correct |
| G2 BT mid-session | `onScanFailed()` sets `running = false`; SecurityException caught | ✅ Correct |
| G3 rapid toggle | `unregisterReceiver` wrapped in try-catch | ✅ Correct |
| G4 rotation | `configChanges="orientation|screenSize|screenLayout"` prevents recreation | ✅ Correct |

---

## Pass/Fail Assessment (Minimum Criteria)

| Criterion | Assessment |
|-----------|-----------|
| A1: Permissions → scanning | ✅ Will pass |
| B1: Wi-Fi AP blip within 10s | ✅ Will pass (if Location ON, Wi-Fi ON) |
| C1: BLE blip within 6s | ✅ Will pass (if BT ON, BLE device present) |
| E1–E3: Baseline set/clear/GONE | ✅ E1/E2/E3 ticker correct; E3 grey blip won't appear (M1) |
| D1/D3/D4: Tracker detection | ✅ Will pass with hardware |
| F1–F3: Radar renders correctly | ✅ F1/F2 pass; F3 grey never renders (M1) |
| G1: BT-off graceful | ✅ Will pass |
| No ANR | ✅ All callbacks on main thread; no blocking I/O |
| No crash | ✅ SecurityException, IllegalStateException, IllegalArgumentException all caught |

**Overall verdict: PASS with noted deviation on grey blips (M1)**

The minimum pass criteria will be met on a physical Samsung S24. M1 is a pre-existing gap documented for follow-up.

---

## Recommended Follow-Up Issues

1. **Fix grey GONE blips not rendering on radar** — `RadarView.updateDevices` needs to accept a separate list of ghost devices to render in grey, or `drawDeviceBlips` needs to iterate DEVICE_GONE anomaly stubs. PATCH semver.

2. **Update on-device-verification-guide.md button labels** — `SET BASELINE` → `SET BASE`, `CLEAR` → `CLR BASE`. No code change needed. PATCH semver.

---

*Analysis performed via static code audit. All code paths traced to source. No physical device access.*
