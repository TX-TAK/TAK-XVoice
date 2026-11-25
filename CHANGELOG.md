# Changelog

All notable user-visible and operationally significant changes to TAK-XVoice are documented in this file.

## [1.1.3]

`versionCode` 33. Supersedes 1.1.2 (versionCode 32 was not releasable).

### Changed
- **Samsung Active Key background and locked-screen PTT now uses Samsung's native key mapping instead of an accessibility service.** XV declares the `HARD_KEY_PRESS` manifest metadata and an exported `HARD_KEY_REPORT` receiver, so it appears under Samsung Settings → Active button → "Use Active button with app"; once assigned, press and release reach XV in the foreground, with ATAK backgrounded, and with the screen off and locked. Validated on a Galaxy Tab Active5 (SM-X308U). No Knox SDK or license is involved.
- The XV settings row for background Active Key PTT now opens Samsung's Active button screen and shows whether the key is assigned to XV.

### Removed
- `SamsungActiveKeyAccessibilityService`, its descriptor and the `BIND_ACCESSIBILITY_SERVICE` declaration. XV no longer requests any accessibility permission. Operators who enabled the old service should assign the key to XV in Samsung Settings instead.

## [1.1.1]

`versionCode` 31. Supersedes 1.1.0 as the shipping build of the Inrico BT02 (B02) speaker-mic feature. No functional change: an internal, behavior-preserving refactor of the BT02 BLE paths (`Bt02BleReader`, `BlePttScanner`, `BitmaskGattPttReader`, `XvMapComponent`) to satisfy the detekt `ReturnCount` and loop-jump rules. The unit suite is unchanged. See [1.1.0] below for the feature itself.

## [1.1.0]

`versionCode` 30. Minor version bump adding experimental hardware support for the Inrico BT02 (B02) budget Bluetooth speaker-mic.

### Added
- **Experimental Inrico BT02 (B02) speaker-mic support** (budget tier, limited support, available for testing, subject to removal):
  - Recognized directly in the Primary Speaker-Mic workflow via Classic Bluetooth pairing (`B02PTT-*`), just like AINA hardware — not exposed in the BLE button scanner or External Button slot.
  - Automatic background discovery and GATT connection to the companion `B02PTT-*` BLE button peripheral (`894c8042-e841-461c-a5c9-5a73d25db08e`) for primary PTT (`0x01`/`0x00`) and one-shot 2-second hold SOS/Emergency (`0x08`, fire-only — cancel in ATAK's Emergency tool). Top button (`0x03`) is intentionally unmapped.
  - Automatic `+5 dB` (`1.78x`) software microphone gain boost in `AudioCapture` when capturing over BT02 Bluetooth SCO to compensate for lower hardware mic gain.
  - Startup cleanup that removes any legacy `B02PTT-*` entries from the BLE-button store / External Button slot.
- Added [docs/hardware/inrico-bt02.md](docs/hardware/inrico-bt02.md) and updated the ranked hardware support table in [README.md](README.md) (AINA V2 #1, AINA V1 #2, Inrico BT02 budget/experimental).

## [0.3.0]

`versionCode` 21. Minor rather than patch: this release drops a
previously-supported device and makes a breaking AIDL schema change,
both user-visible.

### Removed
- Dropped Sonim XP10 (XP9900) support entirely: the four device-specific
  button readers (PTT and Emergency, in both the service-process
  broadcast and ATAK-process foreground-KeyEvent forms), the
  assigned-app receiver, the `Build.BRAND` / `Build.MODEL` device gate,
  the settings row, and the six Sonim methods on `IXvVoice.aidl`.
- `AIDL_API_VERSION` 4 → 5. This is a **breaking** schema change, not an
  additive one — the removed methods sat mid-interface, so every method
  after them shifts Binder transaction ID. `XvVoiceClient`'s
  `EXPECTED_API_VERSION` was realigned to match; it had drifted to 1
  while the service was at 4, so the mismatch warning had been firing on
  every bind and meant nothing.

### Why the Sonim XP10 was retired
- **It did not meet the project's stability bar.** The handset showed
  persistent instability across testing on a clean OS install, not
  traceable to XV's own state machines.
- **It was problematic under performance and endurance testing.**
  Run-to-run behavior was inconsistent enough that the device was
  useless as a reference target — a failure could not be trusted to
  indicate an XV regression, nor a pass to indicate a fix.
- **Bluetooth was the disqualifying problem.** Serious, reproducible
  Bluetooth issues. XV's speakermic and BLE-button integration depends
  on dependable SCO setup/teardown, bonded-device reconnect, and HID/SPP
  event delivery, so a handset with an unreliable Bluetooth stack is a
  poor candidate for custom button integration.
- **End-of-life with no security update path**, which is inconsistent
  with the project's security posture.
- Full rationale and operator-facing impact:
  [docs/hardware/sonim-xp10.md](docs/hardware/sonim-xp10.md).

### Changed
- Hardened TX startup/restart behavior in the audio pipeline:
  - Suppressed false-positive capture restart on initial route-settle (`routedDevice` unresolved to first stable device id).
  - Added restart callback flow from capture to TX controller so mid-burst in-place restarts can re-arm stabilization.
  - Re-applied start-of-stream mitigation after in-place restart (short hold + leading frame drop), and reset TX frame numbering on restart.
- Centralized cold-start mitigation tuning in `ColdStartMitigationPolicy` and routed companion helpers through the policy.

### Why
- Field testing showed a startup race and mid-burst restart path could bypass the normal start-of-stream mitigations, causing garbled onset risk on some devices.

### Validation
- `./gradlew testCivDebugUnitTest --tests TxControllerColdScoWarmupTest --tests TxControllerReadinessBarrierTest`
- `./gradlew assembleCivDebug`
- Deployed to three connected test devices via `./scripts/install-dev.ps1`.

### Notes
- Follow-up verification is expected from fresh post-fix field logs and audio captures to confirm reduction of startup garble recurrence.
