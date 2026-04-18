# Engineering Report — Issue #2: Remove XReal AR, Produce Sideloadable APK

**Date:** 2026-04-17  
**Issue:** justintormey/recon-radar#2

---

## What Was Done

### 1. XReal AR Removal (complete)

Removed all XReal AR rendering code:

| File | Change |
|------|--------|
| `hud/XRealPresentation.kt` | **Deleted** — entire class removed |
| `MainActivity.kt` | Removed `xrealPresentation` + `displayListener` state vars; removed `setupXReal()`, `attachXReal()`, `detachXReal()` methods; removed all XReal call-sites from `beginScanning`, `stopScanning`, `onScanUpdate`, `onResume`, `onDestroy` |
| `hud/HudOverlayView.kt` | Removed `xrealConnected` property; removed "XREAL:LINKED/---" display row from `drawTopBar`; removed unused `cCyan` color constant |

The app is now phone-display-only. No `DisplayManager` listener, no `Presentation` subclass, no display-mirroring code.

### 2. Android 14 Compatibility Audit

Reviewed all scanner code against Android 14 (API 34) restrictions:

| Check | Status |
|-------|--------|
| `BroadcastReceiver` registration (`RECEIVER_NOT_EXPORTED`) | ✅ Already in `WifiScanner` with `Build.VERSION.SDK_INT >= TIRAMISU` guard |
| BLE `BLUETOOTH_SCAN` runtime permission (Android 12+) | ✅ `MainActivity` requests it via `permissionLauncher` |
| BLE `BLUETOOTH_CONNECT` runtime permission (Android 12+) | ✅ Requested alongside `BLUETOOTH_SCAN` |
| Wi-Fi scan throttle (4 per 2 min, Android 10+) | ✅ `WifiScanner` paces: burst 3 at 5s intervals, then 32s intervals |
| `WifiManager.startScan()` deprecated notice | ✅ `@Suppress("DEPRECATION")` present — still the only public API |
| BLE `SecurityException` wrapping | ✅ `BleScanner` wraps start/stop in try-catch |
| `ACCESS_FINE_LOCATION` required for both Wi-Fi and BLE scans | ✅ Manifest + runtime request |

**No code changes needed** for Android 14 compatibility — the scanners were already correct.

### 3. GitHub Actions CI Workflow

Created `.github/workflows/build-apk.yml`:
- Runs on every push to `main` and on manual trigger (`workflow_dispatch`)
- Ubuntu runner + JDK 17 (Temurin) + Gradle cache
- Runs `./gradlew assembleDebug`
- Uploads `app-debug.apk` as a GitHub Actions artifact (30-day retention)

### 4. How to Get the APK

**Via GitHub Actions (recommended):**
1. Push this branch to `main` (or trigger workflow manually)
2. Go to Actions → "Build Sideloadable APK" → latest run
3. Download `recon-radar-debug` artifact
4. Unzip and use `app-debug.apk`

**Via local build** (requires Android SDK + JDK 17):
```bash
export ANDROID_HOME=/path/to/sdk
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 5. Sideloading onto Samsung S24

1. **Enable Developer Options:**  
   Settings → About Phone → Software Information → tap Build Number 7×

2. **Enable USB Debugging:**  
   Settings → Developer Options → USB Debugging → ON

3. **Allow Unknown Sources (for file transfer method):**  
   Settings → Apps → Special Access → Install Unknown Apps → enable for Files app

4. **Install via ADB:**
   ```bash
   adb install app-debug.apk
   ```
   Or copy APK to phone via USB/cable and tap to install.

5. **Permissions to grant on first launch:**
   - Precise Location (required for both Wi-Fi and BLE scanning)
   - Nearby Devices / Bluetooth (required for BLE on Android 12+)

---

## Files Changed

- `app/src/main/java/com/reconradar/app/hud/XRealPresentation.kt` — **deleted**
- `app/src/main/java/com/reconradar/app/MainActivity.kt` — XReal code removed
- `app/src/main/java/com/reconradar/app/hud/HudOverlayView.kt` — `xrealConnected` removed
- `.github/workflows/build-apk.yml` — **new** CI workflow
- `history.md` — updated
