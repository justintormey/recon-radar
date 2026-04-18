# ROGUE_AP Stub Intent — Research Report
## Issue #7: Document ROGUE_AP stub intent

**Date:** 2026-04-18
**Pipeline stage:** Research
**Analyst:** research-analyst agent
**Method:** Static code audit of `SignalBaseline.kt`, `Anomaly.kt`, `SignalBaselineTest.kt`, `history.md`

---

## Status: Resolved

`ROGUE_AP` is **no longer a stub**. It was fully implemented as part of issue #11 (see `history.md` entry dated 2026-04-18). This research report documents the implementation approach, detection quality, and any remaining gaps.

---

## Methodology

1. Read `history.md` for prior decisions and changelog
2. Audited `Anomaly.kt` — confirmed `ROGUE_AP("ROGUE-AP", 3)` with `priority=3` (high priority)
3. Audited `SignalBaseline.kt` — traced `detectRogueAps()` companion function and its integration into `analyze()`
4. Audited `SignalBaselineTest.kt` — counted and categorized test coverage
5. Assessed detection quality: false positive / false negative scenarios

---

## Implementation Summary

### Detection Logic (`SignalBaseline.detectRogueAps`)

```kotlin
companion object {
    fun detectRogueAps(
        devices: List<DetectedDevice>,
        baselineEntries: Collection<BaselineEntry>
    ): List<Anomaly> {
        // For each Wi-Fi device in current scan:
        //  - Skip if BSSID is in the baseline (known AP)
        //  - Skip if SSID is blank (hidden networks)
        //  - Skip non-WiFi device types (BLE excluded)
        //  - Flag if any baseline entry shares same SSID but different BSSID
    }
}
```

**Detection heuristic:** SSID match + new BSSID = Rogue AP (`ROGUE_AP` anomaly, priority 3 / high)

**Integration point:** Called from `analyze()` after the per-device loop, before DEVICE_GONE detection:
```kotlin
if (baseline.isNotEmpty()) {
    anomalies.addAll(detectRogueAps(devices, baseline.values))
}
```

**Architecture note:** Extracted as a pure companion function (no `Context` dependency) so it can be exercised in JVM unit tests without an emulator. Same pattern as `TrackerDetector` (pure Kotlin object) and `bleStableKey()`.

---

## Detection Quality Analysis

### What It Catches (True Positives)

| Scenario | Detected? | Notes |
|----------|-----------|-------|
| Evil Twin attack (same SSID, different BSSID) | ✅ Yes | Primary detection target |
| Multiple rogues in one scan | ✅ Yes | Returns all matching anomalies |
| Rogue on different frequency band | ✅ Yes | Detection is BSSID-based, not channel/band-based |

### False Positive Scenarios

| Scenario | Risk | Mitigation |
|----------|------|-----------|
| Enterprise mesh / WiFi 6 roaming (multiple BSSIDs per SSID by design) | **Medium** | Baseline captures all expected BSSIDs at setup time. If baseline is set in the actual environment, known mesh APs are whitelisted. Risk only exists if baseline is set before all mesh nodes are visible. |
| ISP-provided router + extender with same SSID | **Low** | Same mitigation — baseline captures both if set correctly |
| Neighbor's AP with accidentally identical SSID | **Low** | RSSI will likely be weak (-80 to -90 dBm). No RSSI threshold is applied today (see gap below). |
| Hidden networks | **None** | `device.name.isBlank()` guard eliminates this case entirely |
| BLE devices with same name as a known SSID | **None** | `device.type != WIFI` guard eliminates this |

### False Negative Scenarios

| Scenario | Risk | Notes |
|----------|------|-------|
| Rogue AP with different SSID (deceptive variant) | Not applicable | Out of scope — different SSIDs are not rogue by this definition |
| Rogue AP baselined accidentally | **Low** | If user runs "SET BASE" while a rogue is already present, it gets whitelisted. User-education issue, not a code bug. |
| Rogue AP with blank SSID | **None** | Hidden rogues can't use this attack vector (client must probe for the SSID first anyway) |

---

## Test Coverage

8 unit tests in `SignalBaselineTest.kt` covering:

| Test | What It Verifies |
|------|-----------------|
| `rogue AP detected when new BSSID shares SSID` | Positive detection path |
| `known BSSID is not flagged as rogue AP` | Known BSSID suppression |
| `different SSID does not trigger rogue AP` | SSID mismatch guard |
| `BLE device is never flagged as rogue AP` | Type filter guard |
| `blank SSID is never flagged as rogue AP` | Hidden network guard |
| `empty baseline returns no anomalies` | Empty baseline guard |
| `empty device list returns no anomalies` | Empty device list guard |
| `multiple rogue APs detected in one scan` | Multi-rogue coverage |
| `rogue AP anomaly is high priority` | Priority validation |

**Coverage verdict:** All primary paths and edge cases covered. No gaps identified in test suite.

---

## Identified Gaps

### G1: No RSSI Threshold for Distant Rogues (LOW)

**Issue:** A neighbor's AP with an identical SSID name (accidental collision, not an attack) at -85 dBm will trigger `ROGUE_AP` even though it poses no security risk.

**Current behavior:** Any new BSSID + matching SSID fires the anomaly regardless of RSSI.

**Suggested fix:** Add a configurable RSSI floor (e.g., ignore rogues below -80 dBm). Not a blocker — security-conscious users would want to know even about distant rogues.

**Semver:** MINOR (additive configuration option)
**Priority:** LOW — acceptable false positive given the security use case

### G2: Baseline-Set Timing Ambiguity (LOW)

**Issue:** If the user sets the baseline while a rogue is present (e.g., attacker already deployed), the rogue gets whitelisted. No UX guidance exists to tell the user "set baseline in a trusted state."

**Suggested fix:** Add a note to the HUD or `SET BASE` confirmation message. Out of scope for code — UX/docs issue.

**Semver:** PATCH (documentation/string)
**Priority:** LOW

---

## Recommendations

1. **Close issue #7 as resolved** — ROGUE_AP is fully implemented, tested, and emitted correctly. The original complaint (dead enum variant) no longer applies.

2. **No code changes required** for this research ticket. The implementation is sound.

3. **Optional future enhancement** (G1): Add RSSI threshold parameter to `detectRogueAps()` to suppress distant false positives. MINOR semver, low urgency.

4. **Optional future enhancement** (G2): Update HUD baseline-set confirmation to include "Set baseline in a trusted Wi-Fi environment" guidance. PATCH semver, low urgency.

---

## Semver Classification

| Component | Classification | Reason |
|-----------|---------------|--------|
| ROGUE_AP implementation (already shipped) | MINOR | New detection capability added to existing analyzer |
| G1 RSSI threshold (future) | MINOR | Additive parameter to existing function |
| G2 UX guidance string (future) | PATCH | String resource only, no behavior change |

---

*Analysis performed via static code audit. All code paths traced to source.*
