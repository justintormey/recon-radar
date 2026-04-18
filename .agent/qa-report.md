# QA Report — Issue #13: Location services OFF gives no feedback

**Reviewed commit:** `1403cbd` — "fix: warn user when system Location services are OFF (issue #13)"
**Files changed:** `MainActivity.kt`, `strings.xml`, `history.md`
**Date:** 2026-04-18

---

## Verdict: ✅ APPROVE — ship as-is

No blocking issues. One medium finding (deep link to Settings) and one low finding (semver) documented below; neither blocks merge.

---

## Findings

### ✅ Correctness

`LocationManager.isLocationEnabled` is the correct API to check the OS-level Location toggle. This is distinct from `ACCESS_FINE_LOCATION` permission — the silent-empty-results bug on Samsung One UI / Android 14 exists precisely because permission was granted but the toggle was off. The fix intercepts this at the right level.

Check fires in `beginScanning()` — the single entry point for both the auto-start path (`onCreate` → `requestPermissionsAndStart`) and the button press path (`btnScan.setOnClickListener`). Full coverage of both trigger paths.

### ✅ API level

`LocationManager.isLocationEnabled` added in API 28. `minSdk = 29`. No API-level guard required — correct.

### ✅ State consistency

`scanning = false` is set before returning early, keeping the scan button in "SCAN" state. If this were missing, the button would still say "SCAN" (it was never set to "PAUSE"), so the state wouldn't be visibly wrong — but setting it explicitly is still correct defensive practice.

### ✅ UI feedback

Both `statusText` and a `Toast` are updated. Matches exactly what issue #13 required. Toast uses `LENGTH_LONG` — appropriate for an actionable message.

### ✅ BLE also blocked when Location is OFF

The early return prevents both `WifiScanner.start()` and `BleScanner.start()`. This is correct: BLE scanning also requires Location services enabled on Android. Showing `W:0 B:5` when Wi-Fi was blocked but BLE wasn't would be confusing.

### ✅ String resource

`location_services_off` defined in `strings.xml`. Text: `"Enable Location services for Wi-Fi scan"` — clear, actionable, matches issue spec verbatim.

---

## Medium findings (fix before next release)

### M1 — No Settings deep link

**Severity:** Medium
**Location:** `MainActivity.kt:128-133`

The Toast and status text tell the user what's wrong but provide no way to act. Android provides an intent to open the Location settings screen directly:

```kotlin
startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
```

A small "Go to Settings" action button next to the status text, or an AlertDialog with an action, would significantly improve UX. The issue only required a Toast, so this is not a blocker for this PR, but worth a follow-up issue.

---

## Low findings

### L1 — Semver not bumped

`versionName` stays `1.0.0`. This is a user-visible bug fix. Proper semver = `1.0.1`. However, no release artifacts have been published under 1.0.0 and this is a pre-release sideload project, so no urgency.

### L2 — No instrumented test

MainActivity can't be unit tested with JVM (requires Android runtime). Given the app's test philosophy (pure JVM tests only, no emulator), this is expected. The behavior is simple enough to verify manually.

### L3 — Mid-scan Location toggle not handled

If the user starts scanning successfully, then disables Location in Quick Settings while the app is running, `WifiScanner` will begin returning empty results silently — the same root problem, just in a different phase. This is out of scope for issue #13 but worth a separate follow-up issue. Would require listening to `LocationManager.PROVIDERS_CHANGED_ACTION` broadcast to detect the toggle change at runtime.

---

## Semver gate

No version bump present. Issue #13 is a user-visible bug fix — PATCH per semver (`1.0.0` → `1.0.1`). No breaking changes. Acceptable for a pre-release sideload with no published artifacts.

---

# QA Report — Issue #10: Add missing Gradle wrapper files

**Commit reviewed:** `b67403a`
**Date:** 2026-04-18
**Verdict:** ✅ APPROVED — no blocking findings

---

## Problem Addressed

`gradlew` and `gradle/wrapper/gradle-wrapper.jar` were absent from the repository.
The CI workflow (`.github/workflows/build-apk.yml`) runs both:
```
chmod +x gradlew          # failed: file not found
./gradlew assembleDebug   # failed: file not found
```
Result: zero APKs produced. On-device verification (issue #6) was blocked.

---

## Files Added by This Fix

| File | Size | Notes |
|------|------|-------|
| `gradlew` | 8,784 bytes | POSIX shell script, mode `0755` |
| `gradle/wrapper/gradle-wrapper.jar` | 43,583 bytes | Bootstrap JAR |

`gradle/wrapper/gradle-wrapper.properties` was pre-existing (pinned to 8.11.1).

---

## Correctness Checks

### 1. `gradlew` — executable bit
```
git ls-files --stage gradlew
100755 d95bf6131dc41079a1665889f84d4cfec92a2bc1 0   gradlew
```
Git mode `100755` confirmed. The executable bit is preserved in the index and will survive checkout on any CI runner. CI's `chmod +x gradlew` step is now redundant but harmless. ✅

### 2. `gradlew` — content
- POSIX `#!/bin/sh` shebang. ✅
- Handles Cygwin / MSYS / Darwin / NonStop path translation. ✅
- Invokes `org.gradle.wrapper.GradleWrapperMain` via `-classpath gradle/wrapper/gradle-wrapper.jar`. ✅
- 252 lines matching the official Gradle v8.11.1 `gradlew` template. ✅

### 3. `gradle-wrapper.jar` — structural integrity
```
file: Zip archive data, at least v2.0 to extract, compression method=deflate
```
JAR is a valid ZIP. ✅

Internal entry confirmed present:
```
org/gradle/wrapper/GradleWrapperMain.class   (10,704 bytes)
```
The entry point class `gradlew` references is in the archive. ✅

Size (43,583 bytes) is consistent with official Gradle wrapper JARs.

SHA-256: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`

### 4. Version consistency
`gradle-wrapper.properties`:
```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
```
Matches the version described in the commit and the engineering report. ✅

### 5. CI workflow compatibility
`.github/workflows/build-apk.yml` runs on `ubuntu-latest` with JDK 17 (Temurin). Steps:
1. `chmod +x gradlew` — will now succeed (file exists, executable bit already set). ✅
2. `./gradlew assembleDebug --no-daemon` — wrapper will download Gradle 8.11.1 from services.gradle.org and build. ✅
3. `upload-artifact` uploads `app/build/outputs/apk/debug/app-debug.apk`. ✅

---

## Findings

### Low — `gradlew.bat` absent
`gradlew.bat` (Windows wrapper) was not added. Not needed: CI runs on `ubuntu-latest` and the project is Android-only. If Windows dev support is ever desired, run `gradle wrapper --gradle-version 8.11.1` on Windows.

**Not a blocker.** Low priority.

### Low — No `distributionSha256Sum` in `gradle-wrapper.properties`
Best practice for reproducible builds is to pin the SHA-256 of the distribution ZIP:
```properties
distributionSha256Sum=<sha256 of gradle-8.11.1-bin.zip>
```
This prevents a MITM from serving a tampered Gradle distribution. The official checksum is published at https://gradle.org/release-checksums/.

**Not a blocker.** Low priority for a sideloadable personal tool, but recommended before any production hardening.

---

## Semver Gate

This change adds missing build infrastructure — no app code changed, no API surface changed, no version bump required. ✅

---

## Sign-off

The fix is minimal, correct, and unblocks CI. Both missing files are present, properly structured, and version-consistent with the pre-existing `gradle-wrapper.properties`. Two low-severity gaps noted (no `.bat`, no SHA pin) — neither affects CI or functionality. Ready to ship.
