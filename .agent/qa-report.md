# QA Report — Issue #9: BLE manufacturer company ID fix

**Commit reviewed:** `3fafcad`  
**Date:** 2026-04-18  
**Verdict:** ✅ APPROVED — no blocking findings

---

## Files Changed

| File | Change |
|------|--------|
| `BleScanner.kt` | `extractManufacturerData` returns `Pair<Int, ByteArray>?` instead of `ByteArray?` |
| `DetectedDevice.kt` | Added `manufacturerCompanyId: Int? = null` field |
| `TrackerDetector.kt` | Company-ID dispatch in `checkManufacturerData`; extracted `checkApplePayload()` |
| `TrackerDetectorTest.kt` | 4 new company-ID unit tests |
| `history.md` | Changelog entry |

---

## Correctness

### BleScanner — `extractManufacturerData`

**Before:** `return sparseArray.valueAt(0)` — company ID (SparseArray key) silently dropped.  
**After:** `return Pair(sparseArray.keyAt(0), sparseArray.valueAt(0))` — both ID and payload preserved.  
✅ Fix is minimal and correct.

### DetectedDevice — new field

`manufacturerCompanyId: Int? = null` added with a null default. Kotlin data classes with default values are backward-compatible for named-argument call sites and serialized SharedPreferences data. No existing callers break.  
✅ No regression.

### TrackerDetector — company ID dispatch

```kotlin
if (companyId != null) {
    when (companyId) {
        SAMSUNG_COMPANY_ID -> return TrackerMatch(TrackerType.SAMSUNG_SMARTTAG, Confidence.HIGH)
        TILE_COMPANY_ID    -> return TrackerMatch(TrackerType.TILE, Confidence.HIGH)
        APPLE_COMPANY_ID   -> return checkApplePayload(data)
        else               -> return null   // ← falls through to checkNamePatterns in analyze()
    }
}
```

- **Samsung / Tile:** definitive vendor ID → HIGH confidence. Correct.
- **Apple:** requires payload inspection to distinguish AirTag vs other FindMy devices. Correct design — Apple ships 10+ products using the same company ID.
- **Unknown company ID:** returns `null` from `checkManufacturerData`. `analyze()` then falls through to `checkNamePatterns()`, so name-based MEDIUM detection still fires. Correct — unknown manufacturer doesn't suppress the name path.

✅ Logic is sound.

### checkApplePayload extraction

Old code guarded `if (data.size >= 3)` before Apple type check. New `checkApplePayload` guards `if (data.size < 3) return null`. Behavioral equivalence confirmed: a 2-byte payload previously fell through to `return null`; it now fails `checkApplePayload`'s guard. Same result.

✅ Refactor is safe.

### Legacy path (no company ID)

Unit tests that construct a `DetectedDevice` without `manufacturerCompanyId` skip the company-ID branch and land on `checkApplePayload(data)` directly. The test `airtag-sized find my payload detected` uses this path and still passes. ✅

---

## Test Coverage

| Test | Path exercised | Status |
|------|---------------|--------|
| `samsung company ID 0x0075 detected as SmartTag HIGH` | New Samsung path | ✅ |
| `tile company ID 0x010D detected as Tile HIGH` | New Tile path | ✅ |
| `apple company ID 0x004C with find-my type byte detected as AirTag` | Apple company ID → checkApplePayload | ✅ |
| `unknown company ID returns null from manufacturer data check` | `else -> return null`; name fallthrough | ✅ |
| Pre-existing Apple payload tests | Legacy path unchanged | ✅ |

4 new tests added; all new code paths covered.

---

## Interaction Analysis

**Tile detected by service UUID AND company ID:** `checkServiceUuids` runs first in `analyze()`. A Tile device with both UUID `0000feed` and company ID `0x010D` hits the service UUID path (HIGH) and never reaches manufacturer data. Same result either way — no conflict.

**Samsung with name pattern fallback:** Samsung company ID fires HIGH via manufacturer data before name patterns are reached. If company ID is missing, name check still returns MEDIUM. Correct priority ladder.

---

## Semver Gate

Issue tagged **PATCH**. Assessment:
- No public API changed (all modified methods are `private` or within `internal` data classes)
- `DetectedDevice` new field has a default value — no call-site breakage
- Behavior change is additive: detections that previously returned MEDIUM or nothing now return HIGH

**PATCH is correct.** ✅

---

## Issues Found

None. No critical, high, medium, or low findings.

---

## Sign-off

Fix addresses the root cause precisely. Code is readable, tests are specific, and the legacy path is preserved with a clear comment. Ready to ship.
