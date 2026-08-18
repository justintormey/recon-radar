# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-08-18

SemVer baseline established.

- 2026-03-31: Initial build — Wi-Fi + BLE scanning, tactical radar display, tracker detection, anomaly baseline engine, XReal One Pro HUD via `Presentation` API.
- 2026-04-13: Quality polish (issue #1) — MIT LICENSE, `.gitignore`, JVM unit tests, `history.md`.
- 2026-04-17: Removed XReal AR support, went phone-only, and added a GitHub Actions CI pipeline producing a sideloadable debug APK on every push to `main` (issue #2).
- 2026-04-18: Landed a cluster of detection fixes — BLE company-ID matching for SmartTag/Tile HIGH-confidence detection (#9), ROGUE_AP detection in `SignalBaseline` (#11), BLE MAC-randomization false-positive fix for DEVICE_GONE (#12/#17), and a Location Settings deep link when system Location is off (#13/#22).
- 2026-04-30: Expanded JVM unit test coverage (issue #4).
