# QA Report — Issue #12: BLE MAC Randomization NEW_DEVICE False Positives

**Commit reviewed:** `78a9a44`
**Date:** 2026-04-18
**Verdict:** ⚠️ CONDITIONAL APPROVE — two findings must be tracked; core logic is correct, one medium-severity gap leaves the fix incomplete

---

## Problem Addressed

`SignalBaseline.analyze()` keyed devices by MAC address. Android 14 devices rotate BLE
advertisement MACs for privacy. On rotation:

1. Old MAC expires from `BleScanner.deviceMap` after 15s
2. New MAC → new `DetectedDevice` → `baseline[newMac]` miss → `NEW_DEVICE` anomaly
3. Repeats every rotation cycle, polluting the anomaly ticker with false positives

---

## Implementation Reviewed

### Mechanism 1 — Tracker suppression (`SignalBaseline.kt` lines 103–113)

```kotlin
val isKnownTracker = tracker != null
if (!isKnownTracker) {
    anomalies.add(Anomaly(type = Anomaly.Type.NEW_DEVICE, ...))
}
```

When `TrackerDetector.analyze()` returns non-null (AirTag / SmartTag / Tile / Chipolo),
`NEW_DEVICE` is suppressed. Logic is correct: `KNOWN_TRACKER` is already the meaningful
signal; the duplicate `NEW_DEVICE` is noise.

✅ Correct for: AirTag, SmartTag, Tile, Chipolo  
✅ `OPEN_NETWORK` correctly not suppressed (only fires for WiFi; `isOpen` guards on `type == WIFI`)  
✅ No impact on Wi-Fi baseline behaviour

---

### Mechanism 2 — Composite-key re-match (`SignalBaseline.kt` lines 76–101)

```kotlin
val bleStableIndex: Map<String, BaselineEntry> = baseline.values
    .filter { it.type == DetectedDevice.DeviceType.BLE }
    .mapNotNull { entry ->
        bleStableKey(entry.name, entry.capabilities)?.let { key -> key to entry }
    }
    .toMap()

val entry: BaselineEntry? = baseline[device.id]
    ?: if (device.type == DetectedDevice.DeviceType.BLE)
        bleStableKey(device.name, device.capabilities)?.let { bleStableIndex[it] }
    else null
```

BLE device with stable `name + serviceUUIDs` survives MAC rotation — the rotated device
matches its baseline entry via composite key, no `NEW_DEVICE` fires.

✅ Correct for: Galaxy Buds, Wear OS watches, Tile/Chipolo in stable-name mode  
✅ Correctly skipped when name is empty / `<unnamed>` / caps empty  
✅ `bleStableKey()` extracted as pure companion function (testable without `Context`)  
✅ `BaselineEntry.capabilities` default `""` maintains SharedPreferences backward-compat  

---

## Findings

### FINDING 1 — High: `analyze()` integration not tested; core claims unverified

**Location:** `SignalBaselineTest.kt` lines 201–218

The test titled:
```
`BLE device with rotated MAC re-matches baseline via stable key — no NEW_DEVICE`
```
calls `detectRogueAps()`, **not** `analyze()`. The comment says "The integration path runs
through analyze(); here we confirm the key matches." — but it never invokes `analyze()`.

What is actually tested:
- `detectRogueAps()` returns empty for BLE devices (correct, BLE is filtered there)
- Key equality: `bleStableKey(x) == bleStableKey(x)` (tautologically true)

What is **not** tested:
- That `analyze()` suppresses `NEW_DEVICE` for a rotated BLE MAC via composite key
- That `analyze()` suppresses `NEW_DEVICE` when `KNOWN_TRACKER` fires

If the composite-key fallback branch in `analyze()` were deleted or broken, all 8 new tests
would still pass. The core feature claim is unverified.

**Root cause:** `analyze()` requires `Context` (for SharedPreferences) so it cannot be
tested via JVM unit tests without Robolectric or a refactor that separates the pure analysis
logic from persistence.

**Recommended fix:** Extract the inner analysis loop into a pure companion function (like
`detectRogueAps()` was extracted from issue #11), then test it directly. Alternatively,
add Robolectric as an `androidTestImplementation` dependency.

---

### FINDING 2 — Medium: `DEVICE_GONE` false-positive on MAC rotation (symmetric to NEW_DEVICE gap)

**Location:** `SignalBaseline.kt` lines 147–166

The GONE detection loop:
```kotlin
for ((id, entry) in baseline) {
    if (id !in currentIds) {
        val count = (goneCandidates[id] ?: 0) + 1
        goneCandidates[id] = count
        if (count >= 3) {
            anomalies.add(Anomaly(type = Anomaly.Type.DEVICE_GONE, ...))
        }
    } else {
        goneCandidates.remove(id)
    }
}
```

`currentIds` is built from live device MACs (`devices.map { it.id }.toSet()`). When a BLE
device rotates its MAC:

1. Old baseline MAC → `id !in currentIds` → `goneCandidates[oldMac]++`
2. Repeats every scan cycle (2s)
3. After 3 cycles (~6s): `DEVICE_GONE` fires for the old MAC

The composite-key re-match in the device loop correctly suppresses `NEW_DEVICE` for the
new MAC, but the GONE loop is completely separate and uses only exact-MAC matching. Result:
the fix eliminates `NEW_DEVICE` noise at rotation time but replaces it with `DEVICE_GONE`
noise 6 seconds later — the same device population, the same MAC-rotation trigger.

**Impact:** Galaxy Buds, Wear OS, Tile-in-privacy-mode all produce `DEVICE_GONE` on
every rotation cycle, in addition to the `NEW_DEVICE` noise that was suppressed.

**Recommended fix:**

Track which baseline IDs were composite-key matched during the device loop, and skip those
in the GONE counter:

```kotlin
// After the device loop, before the GONE loop:
val compositeMatchedBaselineIds = mutableSetOf<String>()
for (device in devices) {
    if (device.type == DetectedDevice.DeviceType.BLE && baseline[device.id] == null) {
        val ck = bleStableKey(device.name, device.capabilities)
        val matched = ck?.let { bleStableIndex[it] }
        if (matched != null) compositeMatchedBaselineIds.add(matched.id)
    }
}

// In the GONE loop:
if (id !in currentIds && id !in compositeMatchedBaselineIds) {
    val count = (goneCandidates[id] ?: 0) + 1
    ...
}
```

---

### FINDING 3 — Low: `|` delimiter in `bleStableKey` has theoretical collision risk

**Location:** `SignalBaseline.kt` line 187

```kotlin
return "$n|$c"
```

A device named `"foo|bar"` with caps `"baz"` produces the same key as device `"foo"` with
caps `"bar|baz"`. Real-world BLE device names never contain `|`, but service UUID strings
could theoretically contain it in some future edge case.

**Recommended fix (optional):** Use a more robust separator or hash the composite:
`"$n\u0000$c"` (NUL byte never appears in names or UUIDs), or
`"${n.length}:$n|$c"` (length-prefix the name).

Not a blocker. Marking low.

---

## Test Coverage Summary

| Scenario | Tested | Method |
|----------|--------|--------|
| `bleStableKey` returns non-null for valid name+caps | ✅ | Direct companion fn |
| `bleStableKey` returns null for unnamed / empty caps | ✅ | Direct companion fn |
| `bleStableKey` symmetry | ✅ | Direct companion fn |
| BLE device never flagged as rogue AP | ✅ | `detectRogueAps()` |
| `analyze()`: composite key suppresses NEW_DEVICE | ❌ | NOT TESTED |
| `analyze()`: tracker suppresses NEW_DEVICE | ❌ | NOT TESTED |
| `analyze()`: DEVICE_GONE fires on MAC rotation (regression) | ❌ | NOT TESTED |
| SharedPreferences round-trip for `capabilities` field | ❌ | Not possible without Context |

---

## Correctness of Non-Modified Code Paths

- Tracker detection: unaffected (operates on manufacturer data + service UUIDs, confirmed by issue) ✅
- Wi-Fi baseline: unaffected (composite-key fallback guarded by `device.type == BLE`) ✅
- ROGUE_AP detection: unaffected ✅
- `BleScanner.processResult()`: correctly persists `capabilities = serviceUuids` ✅
- SharedPreferences `${i}_caps` key: correctly saved/loaded with `""` default for back-compat ✅

---

## Semver Gate

- No API surface change
- Bug fix (false positive suppression)
- `versionName` is `1.0.0` — no bump required for an unreleased app ✅

---

## Sign-off

The core logic is correct and addresses the stated problem. Finding 1 (missing `analyze()`
integration tests) means the primary claim is unverified at the test level. Finding 2
(`DEVICE_GONE` false positive) means the fix is incomplete — the same device population
that previously generated `NEW_DEVICE` noise will generate `DEVICE_GONE` noise after MAC
rotation. Both are tracked as follow-up issues.

**Verdict: CONDITIONAL APPROVE** — ship with findings tracked. Finding 2 should be fixed
before on-device verification (issue #6) to avoid confusing the test results.
