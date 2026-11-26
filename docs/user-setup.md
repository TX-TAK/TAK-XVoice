# User Setup & Configuration

Once TAK-XVoice is installed and connected (see [Quick Start](quick-start.md)),
use this guide to configure audio routing, Bluetooth hardware, background PTT,
tones, emergency alerts, and channel sharing.

## Audio Routing ("Phone Stays a Phone")

TAK-XVoice treats your paired Bluetooth speaker-mic as **XV-exclusive comms
gear** while leaving the rest of your phone alone:

- **No held audio resources:** HFP/SCO is engaged **only while the mic is
  actively open** (PTT-down or hot-mic). Loading the plugin or joining a
  channel does not hijack your audio route.
- **Media & navigation keep working:** Spotify, podcasts, GPS turn-by-turn
  prompts, ATAK alerts, and personal headphones continue using their normal
  A2DP / phone audio paths between transmissions.

## Bluetooth Speaker-Mic Picker & Fallback

Open **XV Settings → Primary Speaker-Mic** to pick your preferred Bluetooth
device:

- **Reachability-aware picker:** On plugin load, XV restores your
  last-connected speaker-mic if it is reachable; otherwise it automatically
  selects the first live supported device. Unreachable pairings are dimmed and
  non-tappable in the picker so you can see at a glance what is powered on.
- **Automatic fallback & recovery:** If your selected Bluetooth device becomes
  unreachable for **15 seconds** during a session, XV falls back to the phone
  speaker while remembering your preferred MAC address — audio automatically
  returns to the speaker-mic as soon as it reconnects.
- **Per-MAC button defaults & overrides:** Each supported device ships
  pre-mapped (PTT → Channel 1, secondary → Channel 2, emergency button,
  prev/next channel) so a fresh pairing works out of the box. Any custom button
  overrides you make persist per Bluetooth MAC address.

For device-specific pairing and button notes, see
[Supported Hardware](hardware/README.md).

## Background & Screen-Off Hardware PTT

The **Samsung Active Key** works as PTT in the foreground, with ATAK
backgrounded, and with the screen locked once it is assigned to XV in
Samsung's own settings:

1. Open **XV Settings** and tap **Samsung Active Key PTT** (visible only on
   devices with the key). XV opens Samsung's Active button settings screen.
2. Turn on **Use Active button with app** and pick **Texas TAK-XVoice**.
3. Back in XV Settings the row shows **Assigned**.

!!! info "No special permissions"
    This uses Samsung's built-in key mapping. XV needs no accessibility
    service, Knox license or extra permission for it. Turn the Samsung setting
    off to give the key back to Samsung.

## Auto-Start & Startup Mission

- **Remember last mission:** By default, XV remembers your last-used mission
  and automatically reconnects when ATAK starts — no need to re-open the plugin
  and re-join after every restart.
- **Fleet startup mission:** For managed deployments, you can configure a
  fixed startup mission in **XV Settings** so every device lands on the right
  voice channel on boot.

## Talk Permit Tones

For operators transitioning from public-safety LMR radios, XV provides
configurable **Talk Permit Tones** in **XV Settings**:

- **ASTRO 25**
- **Nextel**
- **DMR**
- **MOTOTRBO**
- **None**

## LMR-Style Emergency Button

Supported speaker-mics include an LMR-style emergency workflow wired directly
into ATAK's **Alert Tool**:

- **Short press:** Fires the emergency beacon configured in ATAK's Alert Tool.
- **Long press (1 second):** Cancels / disarms an active emergency.

!!! warning "Inrico BT02 Exception"
    On the experimental **Inrico BT02**, a 2-second hold on SOS **only fires**
    the emergency — it **cannot** disarm it. You must cancel an active
    emergency from ATAK's Emergency / Alert tool. See
    [Inrico BT02](hardware/inrico-bt02.md).

## Direct Calling

You can place private, 1-to-1 calls to peers on your server or mesh presence
roster:

- Uses Android's `Notification.CallStyle` ring and a full-screen incoming-call
  surface.
- Holds a proximity wake-lock when using the earpiece route so the screen turns
  off when held to your ear.

## Channel Sharing & Offline Comms Plans

Share channels with teammates in two ways:

- **Over the TAK Server (CoT nudge):** Share to **Everyone** (broadcast) or to
  **specific teammates** using ATAK's native contact list. Targeted shares
  **fail closed** — if recipients cannot be resolved to live contacts, nothing
  is broadcast and XV steers you to the offline QR flow instead.
- **Offline QR / Comms Plan:** Export a channel configuration (and optional
  passphrase-locked pre-shared key for encrypted mesh channels) as a QR code or
  shareable plan. Comms plans **never** include TAK/Mumble server credentials
  or certificates.

## Mesh-Voice Failover (Alpha — Testing Only)

!!! warning "Alpha Feature — Off by Default"
    Mesh-voice failover is **experimental and for testing only**. It is off by
    default and has a known bridge issue on congested networks under
    investigation. Do not rely on it operationally until it graduates.

When enabled in **XV Settings**:

- Every joined channel automatically gets a deterministically derived LAN
  multicast leg (`xv-mcast-v1`, mapped to `239.224.0.0/12`, ports
  `16800–16899`) computed from the server hostname and channel name — no
  address exchange required.
- Voice fails over to multicast when the Mumble server is unreachable and fails
  back automatically when the server returns.
- Supports per-frame AEAD encryption (ChaCha20-Poly1305) with TAK-cert-wrapped
  channel keys, distributed key election, auto-bridge election, and pinned
  group/port overrides for OpenMANET / mesh-radio interop (standard RTP
  RFC 3550 + 7587 or raw-Opus wire mode).
