# Quick Start

Get TAK-XVoice installed, connected to your TAK/Mumble server, and transmitting
in a few minutes.

## Before You Begin

Make sure you have:

- **ATAK CIV** installed and enrolled to your TAK Server (see
  [Dependencies](dependencies.md) for supported baselines).
- A **Mumble (Murmur) server** reachable at the **same hostname** as your TAK
  Server on the **standard Mumble port `64738` (TCP/UDP)**. See
  [Server & Channels](server-setup.md) for full server requirements.
- *(Optional)* A supported Bluetooth speaker-mic or ruggedized handset from
  [Supported Hardware](hardware/README.md).

## 1. Install TAK-XVoice

=== "Google Play (Play Store ATAK)"

    1. Install **TAK-XVoice** from Google Play (or your managed Play / TPP
       distribution channel).
    2. Open **ATAK**.
    3. Go to **Settings → Tool Preferences → Plugin Management** (or tap the
       prompt when ATAK detects the newly installed plugin) and **Load**
       **TAK-XVoice (XV)**.

=== "Developer / SDK Build"

    1. Build and install the debug APK against your matching ATAK CIV SDK
       baseline (see [Dependencies → Building from Source](dependencies.md#building-from-source)).
    2. Launch **ATAK CIV Developer Edition** and load **TAK-XVoice (XV)** from
       Plugin Management.

## 2. Grant Required Permissions

When TAK-XVoice loads for the first time, grant the requested Android
permissions:

- **Microphone (`RECORD_AUDIO`)** — required to transmit voice.
- **Nearby Devices / Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`)** —
  required to discover and route audio to Bluetooth speaker-mics and BLE PTT
  buttons.
- **Phone / Call Management (`CALL_PHONE`, `READ_PHONE_STATE`)** — required by
  Android's self-managed VoIP Telecom framework for clean audio focus and
  Bluetooth routing.
- **Notifications (`POST_NOTIFICATIONS`)** — required for the foreground voice
  service and incoming direct-call alerts.
- **Battery Optimization Exemption** — recommended when prompted so Android
  Doze does not drop your voice socket while the screen is off.

## 3. Connect to Your Server & Join a Channel

1. Tap the **TAK-XVoice (XV)** walkie-talkie icon on the ATAK toolbar to open
   the XV drop-down panel.
2. XV automatically discovers your configured TAK Server(s) from ATAK and
   connects to Mumble on **that same hostname, port `64738`**, authenticating
   with your device's ATAK TLS enrollment certificate.
   - If you have multiple TAK Servers configured in ATAK, open **XV Settings**
     to pick a preferred server host.
3. Tap the channel name on the main panel to open the channel list, then tap
   **1** on a channel to make it channel 1 (keyed by PTT 1). Optionally tap
   **2** on another channel for channel 2 (keyed by PTT 2).

!!! note "No separate Mumble login needed"
    Because XV reuses your ATAK enrollment certificate for TLS client
    authentication, you do not need to enter a separate Mumble password. The
    server's TLS certificate must match the hostname you connect to.

## 4. Pair Your Speaker-Mic (Optional)

- **AINA APTT V2 / V1 or Inrico BT02:** Pair the speaker-mic in **Android
  Bluetooth Settings** first, then select it on the **Speaker-mic** card in
  **XV Settings → Devices**.
- **Pryme BT-PTT-Z (BLE button only):** Follow the
  [Pryme BT-PTT-Z Pairing Guide](hardware/pryme-bt-ptt-z.md).
- **Samsung Active Key:** In **XV Settings → Devices → Phone Active key**,
  tap the card to open Samsung's Active button settings and assign the key to
  XV; see [Samsung Active Key](hardware/samsung-active-key.md) for background /
  screen-off setup.

## 5. Test Push-to-Talk

1. Press and hold the on-screen **PTT** button (or your paired speaker-mic /
   hardware PTT key).
2. Listen for the **Talk Permit Tone** (if enabled) and speak.
3. Release to stop transmitting.

Next: tune audio routing, auto-start missions, talk-permit tones, and emergency
buttons in [User Setup](user-setup.md).
