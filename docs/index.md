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

- [Privacy Policy](privacy.html)
- [Terms of Service](terms.html)
- [GitHub Repository & Issue Tracker](https://github.com/TX-TAK/TAK-XVoice)
