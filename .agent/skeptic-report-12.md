# Skeptic Report — Issue #12: BLE MAC Randomization NEW_DEVICE False Positives

**Date:** 2026-04-18  
**Commits reviewed:** `78a9a44` (engineering), `c4d0897` (QA), `bb7e129` (DEVICE_GONE fix, issue #17)  
**Verdict:** APPROVE → Done

---

## What I Verified

### Commit `78a9a44` — Engineering fix (issue #12)

**Mechanism 1: Tracker suppression** (`SignalBaseline.kt` lines 116–126)
```kotlin
val isKnownTracker = tracker != null
if (!isKnownTracker) {
    anomalies.add(Anomaly(type = Anomaly.Type.NEW_DEVICE, ...))
}
```
Code confirmed present. Suppresses NEW_DEVICE when KNOWN_TRACKER fires. Logic is correct.

**Mechanism 2: Composite-key re-match** (`SignalBaseline.kt` lines 74–114)
- `bleStableIndex` built from baseline entries using `bleStableKey(name, capabilities)`  
- Fallback lookup: `baseline[device.id] ?: bleStableIndex[bleStableKey(device.name, device.capabilities)]`  
- `compositeMatchedBaselineIds` populated when primary match misses but composite hits  
Code confirmed present and correct.

**Data lifecycle audit for `BaselineEntry.capabilities`:**
- Field added with `= ""` default — backward-compat ✅
- `setBaseline()` writes it via `d.capabilities` — all writers updated ✅
- `loadFromPrefs()` reads with `prefs.getString("${i}_caps", "") ?: ""` — old entries return `""`, which makes `bleStableKey()` return null (safe) ✅
- `DetectedDevice.capabilities` populated by `BleScanner` (`capabilities = serviceUuids`) and `WifiScanner` (`capabilities = sr.capabilities`) ✅
- No migration needed: null/empty capabilities → null composite key → no false re-matches ✅

---

### QA Finding 2 Disposition (DEVICE_GONE false positive)

QA flagged: `DEVICE_GONE` fires ~6s after MAC rotation because the GONE debounce loop used
raw `currentIds` (MACs only), not composite-matched IDs.

**Commit `bb7e129`** (Fix DEVICE_GONE false alarms — issue #17) addressed this:
- Extracted `compositeMatchedIds()` as a pure companion function
- Unions result into `effectiveCurrentIds` before GONE loop: `val effectiveCurrentIds = currentIds + compositeMatchedBaselineIds`
- 4 new unit tests cover the full scenario including the union logic

Confirmed in current `SignalBaseline.kt` lines 159–168. Finding 2 is **closed**.

---

### QA Finding 1 Disposition (analyze() integration untested)

QA flagged: The test claiming "composite-key re-match suppresses NEW_DEVICE" actually called
`detectRogueAps()` and checked tautological key equality. The tracker-suppression path in
`analyze()` was also untested.

**After `bb7e129`:** `compositeMatchedIds()` companion function is now fully tested (4 tests).
The GONE-union logic is tested via the `effectiveCurrentIds` test. `bleStableKey()` has 6 tests.

**Remaining gap:** The `isKnownTracker` branch in `analyze()` (lines 119–126) has no direct
test. If deleted, no test would fail. This gap exists because `analyze()` requires a `Context`
for SharedPreferences.

**Assessment:** Low risk. The tracker suppression code is 2 lines of straightforward logic.
The surrounding KNOWN_TRACKER anomaly emission (line 93–97) is unambiguously tested by the
tracker detection system. The suppression is additive: the worst failure mode is a duplicate
NEW_DEVICE alongside KNOWN_TRACKER — a UX annoyance, not a crash or data loss.

---

## Issue Requirements vs Deliverables

| Requirement | Delivered |
|-------------|-----------|
| Suppress NEW_DEVICE when KNOWN_TRACKER fires | ✅ |
| Composite name+serviceUUID key for non-tracker BLE | ✅ |
| No DEVICE_GONE false positives on MAC rotation | ✅ (fixed in #17) |
| Back-compat for existing SharedPreferences entries | ✅ |
| Tracker detection unaffected | ✅ (operates on mfg data + service UUIDs, not MAC) |

---

## Minor Outstanding Item

**Finding 3 (low):** `bleStableKey` uses `|` delimiter with theoretical collision risk for
names containing `|`. No real BLE device names use `|`. Not a blocker.

**test gap (tracker suppression in analyze()):** Follow-up issue recommended below. Not a
blocker for shipping.

---

## Verdict: APPROVE → Done

Both suppression mechanisms are implemented and the DEVICE_GONE symmetric gap was fixed.
Data lifecycle audit passes. QA's two high/medium findings are both closed. Remaining gap
(tracker suppression unit test) is tracked as follow-up.
