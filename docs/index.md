# TAK-XVoice

**TAK-XVoice (XV)** is an ATAK voice plugin focused on operational use cases
that existing options don't cover well: clean Bluetooth audio routing,
predictable auto-reconnect, curated external PTT hardware, and a unified
Mumble + multicast voice experience.

!!! warning "Status & Intended Use"
    **TAK-XVoice is provided for non-mission-critical use only.** It is
    distributed under the Apache License 2.0 with **no warranty of any kind**.
    Do not rely on this plugin as the sole means of communication for any
    life-safety, public-safety, military, or other mission-critical
    application. Operators assume all risk associated with its use.

## Where to Start

- **[Quick Start](quick-start.md)** — Install the plugin, connect to your
  TAK/Mumble server, and make your first PTT transmission.
- **[User Setup](user-setup.md)** — Configure Bluetooth audio routing,
  auto-start missions, talk-permit tones, emergency buttons, and channel
  sharing.
- **[Server & Channels](server-setup.md)** — Mumble host/port requirements
  (same hostname as your TAK server on standard port `64738`), TLS cert
  authentication, and channel synchronization with TAK Server groups.
- **[Supported Hardware](hardware/README.md)** — Curated Bluetooth
  speaker-mics, BLE PTT pucks, and ruggedized handset keys.
- **[Dependencies](dependencies.md)** — ATAK versions, Android permissions,
  server prerequisites, and source build setup.

## What Makes TAK-XVoice Different

- **Phone stays a phone:** Your Bluetooth speaker-mic is treated as
  XV-exclusive comms gear. HFP/SCO only engages while the mic is actually open
  (PTT-down or hot-mic), so music, navigation prompts, and ATAK alerts keep
  flowing on their normal audio paths.
- **Predictable auto-reconnect:** Remembers your last-used mission and
  auto-reconnects on plugin start, with an optional configurable startup
  mission for fleet-managed deployments.
- **Curated PTT hardware:** Per-MAC button registration with vetted defaults
  for supported Bluetooth speaker-mics (AINA APTT V2/V1, Pryme BT-PTT-Z,
  experimental Inrico BT02) and ruggedized handset keys (Samsung Active Key),
  plus an optional Accessibility Service for background/screen-off PTT.
- **LMR-familiar UX:** Configurable Talk Permit Tones (ASTRO 25, Nextel, DMR,
  MOTOTRBO, or None), LMR-style emergency button wired into ATAK's Alert Tool,
  and direct calling.
- **Mumble + multicast under one UX:** Mumble authenticates with your device's
  ATAK enrollment certificate; optional alpha mesh-voice failover provides
  deterministic multicast voice (`xv-mcast-v1`) when the server is unreachable.

## Hardware Philosophy

The goal is **not** to claim support for every Bluetooth PTT device on the
market. Each device listed under [Supported Hardware](hardware/README.md) is
deliberately curated — sourced, integrated, and validated against real event
traffic — so operators can trust that what's listed actually works under load.
Devices that fail to meet that bar are retired with a public rationale.

## Legal & Project Links

- [Privacy Policy](#privacy-policy) (also below) / [standalone page](privacy.html)
- [Terms of Service](terms.html)
- [GitHub Repository & Issue Tracker](https://github.com/TX-TAK/TAK-XVoice)

<!--
  INTERIM: the full privacy policy is inlined on the home page below so the
  site-root URL (https://tx-tak.github.io/TAK-XVoice/) resolves directly to a
  privacy policy. This keeps the Google Play "privacy policy" link valid while
  that link still points at the site root. Once the app is published and the
  Play listing is repointed at /privacy.html, remove this inlined section and
  leave only the link above. Tracked in the GitHub issue referenced in PR #136.
-->

## Privacy Policy

TAK-XVoice ("XV", "the plugin") is an open-source voice plugin for the Android
Team Awareness Kit (ATAK), distributed under the Apache License 2.0. This policy
explains what the plugin does and does not do with your data. A standalone copy
lives at [privacy.html](privacy.html).

Last updated: 13 November 2026

### The short version

XV does not collect, store, or transmit personal information to its developers. It has no analytics, no advertising, and no crash-reporting services. To carry your voice, XV connects to the voice server you configure and sends that server -- and the other people on your channel -- your live voice audio and the identity you connect under (your ATAK callsign plus a device-derived identifier). Nothing is ever sent to the plugin's developers.

### What the plugin accesses, and why

- Microphone / audio. When you press to talk (or a call is active), XV captures audio and transmits it in real time to the voice server you have configured in ATAK. This audio is not recorded to storage by the plugin and is not sent to the plugin's developers.

- Bluetooth. Used only to connect to and read button presses from the Bluetooth push-to-talk hardware you pair. No Bluetooth data leaves your device.

- Phone / call state. Used only to detect whether a cellular call is in progress (so push-to-talk behaves correctly) and to register XV's voice session with Android's self-managed calling framework, which lets the plugin manage its own audio routing while you transmit. The plugin does not read your phone number, call log, or contacts, and does not place telephone calls.

- Network state. Read only to react to Wi-Fi / cellular / offline transitions. The plugin does not change your network settings.

- Notifications, full-screen intent, wake lock, battery-optimization exemption. Used to show call and status notifications and to keep a voice session alive while the screen is off. No data is collected.

### Location

XV does not request or use location. Bluetooth scanning is declared with the "never for location" flag.

### Data sharing

The plugin shares no data with its developers. It has no developer-side servers and receives nothing from you.

To use XV you connect to a voice (Mumble/TAK) server that you configure, operated by you or your organization. So that server and the other participants on your channel can carry your voice and show who is speaking, XV sends them:

- your voice audio, in real time -- never recorded or stored by XV;

- your connection identity -- your ATAK callsign combined with a device-derived identifier (a one-way hash of your device's ATAK ID, which gives you two voice "slots" and lets other XV users match your voice to your map presence). If you turn on the optional legacy-compatibility mode, your raw ATAK device ID is also sent for the same mapping.

This information goes only to the server you configure and the peers on it -- never to the plugin's developers. How that server and those peers handle it is governed by their own policies; a server operator or another participant could record what they receive, which is outside XV's control.

### Data retention and deletion

The plugin stores only your local settings (such as device pairings and preferences) on your own device. Uninstalling the plugin removes them. There is no developer-side account or storage, so there is nothing for the developers to retain or delete.

### Security

Voice traffic to a Mumble server is encrypted in transit with TLS. The optional mesh-voice feature additionally encrypts each audio frame with authenticated encryption (ChaCha20-Poly1305).

### Children

XV is a tool for adult communications volunteers and is not directed at children.

### Changes

This policy may be updated as the plugin evolves. Material changes will be reflected here with a new "last updated" date.

### Contact

Questions about this policy: support@texas-tak.com. Source and issue tracker: [github.com/TX-TAK/TAK-XVoice](https://github.com/TX-TAK/TAK-XVoice).
