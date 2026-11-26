# Changelog

All notable user-visible and operationally significant changes to TAK-XVoice are documented in this file.

## [Unreleased]

### Fixed
- **OpenMANET interop: XV audio is no longer silently dropped by openmanetd.** The patch/interop leg used `VX_COMPAT`, which sends bare Opus; openmanetd parses every datagram as RTP and discards anything else. New cleartext `WireFormat.OPENMANET_RTP` profile (RTP, payload type 111, no XVMC control packets) is now used for patch legs created from the app. `VX_COMPAT` is unchanged (raw Opus) for legacy VX peers; existing stored configs keep their wire format.
- OpenMANET leg packs two consecutive 10 ms Opus frames into one 20 ms packet (RFC 6716 code 1/2, no re-encode), matching openmanetd's 20 ms cadence. When the encoder changes Opus config between two frames they can't share a packet; the older one is sent alone as a 10 ms packet rather than dropped. The Mumble path still encodes and sends 10 ms frames. The RTP timestamp now advances by the actual packet duration instead of a fixed 960.
- OpenMANET leg keeps RTP sequence/timestamp monotonic across PTT bursts (random initial values) so rapid re-keys are not discarded by openmanetd's jitter buffer, which only re-syncs on an SSRC change or ~2 s idle.
- Multicast TTL raised 8 → 32 (multi-hop batman-adv / overlay paths) and DSCP EF (`0xB8`) is requested on mesh sockets, best-effort (failure is logged, not fatal).
- Patch legs are now reset on every PTT-down (a half-pair held by the OpenMANET codec could previously leak into the next burst), are rebuilt when a patch is re-applied on the same group:port with a different wire format, and are validated before being persisted (an invalid patch could previously take the channel's primary mesh leg down). The channel-config dialog's patch fields also default to `OPENMANET_RTP`.
- Relay TX on a leg is now serialized (it is reachable from both the Mumble and multicast receive threads), and merged 20 ms packets are capped at openmanetd's 1275-byte payload limit.
- Applying a patch to a channel that uses the auto-derived default config no longer fails with "Channel config not found."
- **Mesh security: the XV mesh / failover leg is always encrypted.** Every XV-native mesh leg, including every auto-derived failover leg, is now "Encrypted only": it never sends or accepts cleartext voice, and until a channel key is held it is silent (KEY NEEDED) rather than falling back to the clear. The old "Prefer encrypted" posture sent and accepted cleartext RTP until keying converged (for example, a newcomer waiting on the key exchange). Cleartext now exists only on interop patch legs (OpenMANET RTP / VX compat), which have no encryption layer. Both channel dialogs drop the encryption choice and show encryption as always on. Mixed-version note: an older XV peer that has no key yet sends cleartext, which updated peers drop.
- **Mesh security: stored channels are brought in line once on upgrade.** Every stored XV-native channel is moved to "Encrypted only", including ones set to "Prefer encrypted" or cleartext. A channel whose primary leg had been switched to VX / OpenMANET framing (for example by the VX-interop prompt) becomes an encrypted XV mesh channel plus a cleartext patch leg on the same group/port. If that channel already had a patch leg it is left as is and logged, because two interop endpoints do not fit one channel; clearing its patch in the per-channel settings and saving completes the move. Configs are also normalized on every read and write, so a stale value written by an older build cannot reach a live leg. A separate per-channel crypto-policy preference that disagreed with the channel config is removed.
- Changing a mesh channel's wire format or mode now takes effect immediately: the live leg is rebuilt (keeping its key) instead of running the old codec until restart.
- An unkeyed mesh channel now shows KEY NEEDED when it hears encrypted peers (encrypted frames were being counted as a policy drop), and is no longer flagged red CLEAR: it never transmits in the clear. CLEAR now lights only for legs that actually send cleartext (interop patch legs).
- Provisioning a channel under a name whose key is already held no longer drops that key from the sealed key vault, and channel-plan exports carry the channel's current settings rather than the snapshot taken when it was provisioned.
- **OpenMANET / VX patch settings are device-local.** A channel's patch leg (group, port, framing, policy) never leaves the device: it is stripped from channel-plan exports (QR / text / .xvplan) and any re-share, and ignored when importing a plan from an older build that still carries it. Importing a plan never wipes this device's own patch. An interop primary from an older build is shared as an encrypted XV mesh channel without its interop endpoint. This keeps OpenMANET gatewaying to one manually configured device at a time, so two devices set up from the same plan cannot both feed one OpenMANET net into the server or mesh. The CoT teammate share already carried channel names only.
- **Mesh security: editing an offline channel no longer re-keys it.** Tapping a channel in the Mesh grid and saving minted a fresh pre-shared key (cutting it off from every peer holding the current one), forced it to be the primary channel, and reset its mode and patch leg. Editing now keeps the key, mode, patch leg and primary selection, and never mints a key; rotation stays the explicit Rotate action. Provisioning a new channel under a name whose key is already held also keeps that key.
- The "Configure a channel" form no longer prefills the channel's group/port with its patch address when the channel has no pin (saving it pinned the channel to the patch endpoint). Editing a channel with no stored config also shows its name instead of a blank, disabled field.

### Notes
- New `scripts/interop-sim/run.sh` replays XV's OpenMANET-leg datagrams through openmanetd's real RTP/jitter/Opus receive code with no radios or nodes.
- Not yet verified on-air against a live openmanetd node.
- Configs naming `OPENMANET_RTP` are not readable by older builds; they degrade to the auto-derived default channel config rather than crashing.

## [1.1.3]

`versionCode` 33. Supersedes 1.1.2 (versionCode 32 was not releasable).

### Changed
- **Samsung Active Key background and locked-screen PTT now uses Samsung's native key mapping instead of an accessibility service.** XV declares the `HARD_KEY_PRESS` manifest metadata and an exported `HARD_KEY_REPORT` receiver, so it appears under Samsung Settings → Active button → "Use Active button with app"; once assigned, press and release reach XV in the foreground, with ATAK backgrounded, and with the screen off and locked. Validated on a Galaxy Tab Active5 (SM-X308U). No Knox SDK or license is involved.
- Samsung Active Key setup is now a single XV settings row, **Samsung Active Key PTT**, which shows whether the key is assigned to XV and opens Samsung's Active button screen. Samsung's own "Use Active button with app" setting is the only on/off switch; the key does nothing in XV until it is assigned.
- `AIDL_API_VERSION` 5 → 6 (breaking: Samsung Active Key methods removed mid-interface).

### Removed
- `SamsungActiveKeyAccessibilityService`, its descriptor and the `BIND_ACCESSIBILITY_SERVICE` declaration. XV no longer requests any accessibility permission. Operators who enabled the old service should assign the key to XV in Samsung Settings instead.
- The XV-side "Use Samsung Active Key as PTT" toggle and its persisted preference, the runtime `HARD_KEY_REPORT` reader (`SamsungActiveKeyReader`) and the foreground-KeyEvent reader (`SamsungActiveKeyForegroundReader`), plus the `setSamsungActiveKeyEnabled` / `isSamsungActiveKeyRunning` / `notifySamsungActiveKeyEdge` AIDL methods. They duplicated the manifest receiver, and the XV toggle did not actually stop background PTT once the key was assigned.

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
