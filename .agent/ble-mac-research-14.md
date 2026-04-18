# Research: BLE MAC Randomization — Issue #14

**Issue:** justintormey/recon-radar#14  
**Date:** 2026-04-18  
**Stage:** Research  
**Analyst:** Research Agent

---

## Question

Samsung S24 BLE MAC rotation creates perpetual `NEW_DEVICE` anomalies in baseline mode. Does the existing implementation (issue #12) fully address this? What gaps remain?

---

## Methodology

1. Read `SignalBaseline.kt` (full), `BleScanner.kt`, `DetectedDevice.kt`, `Anomaly.kt`
2. Traced all paths that produce `NEW_DEVICE` and `DEVICE_GONE` anomalies
3. Checked `goneCandidates` state machine against composite-key re-match logic
4. Reviewed unit tests in `SignalBaselineTest.kt` for coverage gaps
5. Cross-referenced `history.md` for prior engineering decisions (issues #12, #16)

---

## Background: How BLE MAC Rotation Breaks Baseline Tracking

Android 14 implements **BLE address randomization** as a privacy feature. A device like Samsung Galaxy Buds2 or a Wear OS watch advertises with a randomly-generated MAC that rotates on a schedule (typically every 15 minutes, but configurable per peripheral). The `BluetoothLeScanner` API exposes the **current advertisement MAC** as `device.address` — there is no stable Android public API to recover the original hardware address for third-party peripherals.

### The Break Chain (pre-fix)

```
Baseline at T₀:    MAC = AA:BB:CC:DD:EE:01 → baseline["AA:BB:CC:DD:EE:01"]
MAC rotates at T₁: MAC = FF:11:22:33:44:55 (same physical device)

analyze() at T₂:
  baseline["FF:11:22:33:44:55"] → null
  → NEW_DEVICE fired ← FALSE POSITIVE
  goneCandidates["AA:BB:CC:DD:EE:01"] = 1

analyze() at T₃:   (still rotated)
  → NEW_DEVICE fired ← FALSE POSITIVE
  goneCandidates["AA:BB:CC:DD:EE:01"] = 2

analyze() at T₄:   (still rotated)
  → NEW_DEVICE fired ← FALSE POSITIVE
  goneCandidates["AA:BB:CC:DD:EE:01"] = 3
  → DEVICE_GONE fired for AA:BB:CC:DD:EE:01 ← FALSE POSITIVE
```

---

## What Issue #12 Already Fixed

Commit `78a9a44` introduced two suppression mechanisms in `SignalBaseline.analyze()`.

### Mechanism 1: Composite-Key Re-match (NEW_DEVICE suppression)

```kotlin
val bleStableIndex: Map<String, BaselineEntry> = baseline.values
    .filter { it.type == BLE }
    .mapNotNull { entry ->
        bleStableKey(entry.name, entry.capabilities)?.let { it to entry }
    }.toMap()

// Primary lookup by MAC; fallback to stable key for BLE
val entry: BaselineEntry? = baseline[device.id]
    ?: if (device.type == BLE)
           bleStableKey(device.name, device.capabilities)?.let { bleStableIndex[it] }
       else null
```

**Result:** If a BLE device has both a stable name AND service UUIDs, its rotated MAC is re-matched to the baseline entry — `NEW_DEVICE` is NOT fired. ✅

### Mechanism 2: Tracker Suppression

```kotlin
val isKnownTracker = tracker != null
if (!isKnownTracker) {
    anomalies.add(Anomaly(type = NEW_DEVICE, ...))
}
```

**Result:** AirTag, SmartTag, Tile — which rotate MACs and often lack stable names — are suppressed at the tracker detection layer. ✅

### Stable Key Design

```kotlin
fun bleStableKey(name: String, capabilities: String): String? {
    val n = name.trim()
    val c = capabilities.trim()
    if (n.isEmpty() || n == "<unnamed>" || c.isEmpty()) return null
    return "$n|$c"
}
```

Requires BOTH a non-empty name AND non-empty service UUIDs. This is intentional — using name alone risks false matches (two different "Sony WH-1000XM5" headphones in the same space would collide).

---

## Residual Gaps

### Gap 1 (CRITICAL): DEVICE_GONE False Alarms After MAC Rotation

**Status: NOT fixed by issue #12**

The `goneCandidates` map is keyed by raw MAC and updated independently of the composite-key re-match:

```kotlin
// Lines 148-165 of SignalBaseline.kt
for ((id, entry) in baseline) {
    if (id !in currentIds) {        // currentIds is MAC-keyed — misses rotated MACs
        val count = (goneCandidates[id] ?: 0) + 1
        goneCandidates[id] = count
        if (count >= 3) {
            anomalies.add(Anomaly(type = DEVICE_GONE, ...))  // fires on old MAC
        }
    } else {
        goneCandidates.remove(id)
    }
}
```

When a device's MAC rotates:
- New MAC is composite-key matched → `NEW_DEVICE` suppressed ✅
- Old MAC is NOT in `currentIds` → `goneCandidates[oldMac]` increments every cycle
- After 3 scan cycles (~6 seconds at 2s delivery interval) → `DEVICE_GONE` fires ❌

**Impact:** Every MAC rotation of a named BLE device (Galaxy Buds, Wear OS watch) produces a spurious `DEVICE_GONE` — visible as a grey ghost blip on the radar (rendered since issue #16) and a "disappeared" log entry in the HUD ticker.

**Proposed fix:** Build a set of baseline IDs that were "seen" via composite key by any live device, and treat those as effectively present before the DEVICE_GONE loop:

```kotlin
// In analyze(), after building bleStableIndex and processing live devices:
val compositeMatchedBaselineIds: Set<String> = devices
    .filter { it.type == BLE }
    .mapNotNull { d ->
        bleStableKey(d.name, d.capabilities)?.let { key -> bleStableIndex[key]?.id }
    }.toSet()

val effectiveCurrentIds = currentIds + compositeMatchedBaselineIds

// Then use effectiveCurrentIds instead of currentIds in the DEVICE_GONE loop
```

**Test cases needed:**
- After MAC rotation, composite-matched device does NOT generate DEVICE_GONE
- Old MAC goneCandidates counter resets (not just suppressed) when composite match is live
- Unmatched BLE device (no UUID) still generates DEVICE_GONE correctly after 3 cycles
- Named device that disappears (not just rotation) still generates DEVICE_GONE correctly

**Complexity:** PATCH — pure internal logic change, no Android API dependency, fully JVM-testable.

---

### Gap 2 (LOW): Radar Angle Jitter on MAC Rotation

`DetectedDevice.radarAngle` is derived from `id.hashCode()`:

```kotlin
val radarAngle: Float get() = (id.hashCode().toUInt() % 360u).toFloat()
```

When MAC rotates, even if `NEW_DEVICE` is suppressed, the live device object has a new `id`. The radar plots it at a different angle, so the device visually "jumps" at each rotation cycle. This is cosmetic noise, acceptable for a sideloaded security scanner.

**Fix approach:** When composite-key matched, re-use the baseline entry's `id` for angle computation. Requires a new field on `DetectedDevice` or a parallel "stable ID" concept — non-trivial plumbing. Not recommended for current scope.

**Complexity:** MINOR (new field + plumbing)

---

### Gap 3 (BY DESIGN): Named-Only Devices Still Generate NEW_DEVICE

Devices that advertise with a stable name but no service UUIDs (e.g. some Bluetooth speakers, unspecified Samsung peripherals in Bluetooth classic advertising mode) cannot form a stable composite key. `bleStableKey()` returns null when `capabilities` is empty. This is intentional — collision risk with name alone. Documented here for completeness.

---

### Gap 4 (LOW): BLE Advertisement UUID Inconsistency

BLE devices don't include service UUIDs in every advertisement packet. If the baseline was captured from a packet with no UUIDs, `capabilities = ""` → `bleStableKey` returns null → composite re-match fails → NEW_DEVICE fires even though #12's mechanism is in place.

Root cause: `BleScanner.processResult()` captures UUIDs from the most recent `ScanResult` only:

```kotlin
val serviceUuids = result.scanRecord?.serviceUuids
    ?.joinToString(",") { it.toString() } ?: ""
```

Each new `ScanResult` for the same MAC overwrites `deviceMap[address]` entirely. If the latest packet happened to omit UUIDs, `capabilities` is reset to `""`.

**Fix approach:** Accumulate service UUIDs as a union across packets for the same MAC, within the 15-second window. Requires a secondary UUID accumulator map in `BleScanner`, or merging capabilities on `deviceMap` update.

**Complexity:** PATCH — internal to BleScanner, no downstream API change.

---

## Summary Table

| Gap | Severity | Status | Fix Complexity |
|-----|----------|--------|----------------|
| NEW_DEVICE on MAC rotation (named + UUID devices) | HIGH | ✅ Fixed (#12) | — |
| NEW_DEVICE suppression for known trackers | HIGH | ✅ Fixed (#12) | — |
| **DEVICE_GONE false alarm after MAC rotation** | **HIGH** | **❌ Unfixed** | **PATCH** |
| Radar angle jitter on rotation | LOW | ❌ Unfixed | MINOR |
| Named-only device coverage (no service UUIDs) | LOW | BY DESIGN | n/a |
| UUID overwrite on repeated advertisement packets | LOW | ❌ Unfixed | PATCH |

---

## Recommendation

### Immediate: Fix DEVICE_GONE False Alarms (Gap 1)

This is a complete implementation of issue #14's core scenario. The NEW_DEVICE half is fixed; the DEVICE_GONE half is not. Without this fix, every BLE MAC rotation still produces visible noise — the grey ghost blip problem that issue #16 now renders correctly makes this more visible, not less.

**Engineering scope:** One new local variable (`compositeMatchedBaselineIds`) + change one identifier in the DEVICE_GONE loop (`currentIds` → `effectiveCurrentIds`). Estimated ~15 lines of code change + 3-4 new unit tests.

### Deferred

- **UUID accumulation (Gap 4):** Reduces false positives for devices with inconsistent advertisement packets. Worthwhile but low frequency; defer to a follow-up issue.
- **Radar jitter (Gap 2):** Polish concern. Skip for sideloaded tool scope.

---

## Semver Classification

- Gap 1 fix: **PATCH** — internal logic only, no API or persistence format change
- Gap 4 fix: **PATCH** — internal BleScanner change
- Gap 2 fix: **MINOR** — adds field to DetectedDevice (additive)

---

## Files Reviewed

| File | Role |
|------|------|
| `scanner/SignalBaseline.kt` | Baseline comparison engine, composite-key logic |
| `scanner/BleScanner.kt` | Raw BLE scan, UUID capture |
| `model/DetectedDevice.kt` | Device data model, `radarAngle` derivation |
| `model/Anomaly.kt` | Anomaly type registry |
| `test/scanner/SignalBaselineTest.kt` | Existing test coverage |
| `history.md` | Issue #12, #16 changelogs |
