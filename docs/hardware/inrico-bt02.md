# Inrico BT02 (B02) — Budget / Experimental Speaker-Mic

> **Status: Experimental / Limited Support — Subject to Removal.**
> The Inrico BT02 (also sold as the Inrico B02) is included as a **budget
> option for testing** because of community interest in supporting
> lower-cost Bluetooth speaker-mics. It is **not** a recommended
> replacement for AINA hardware, receives limited support, and may be
> removed in a future release if it proves unreliable in field traffic.
> For production use, choose the **AINA PTT Voice Responder V2** (#1
> recommended) or **AINA PTT Voice Responder V1** (#2 recommended).

## Why it's included (and what "budget option" means)

Operators frequently ask whether XVoice can work with inexpensive
Zello-style Bluetooth speaker-mics. The Inrico BT02 meets the minimum
criteria for basic testing (Classic HFP speaker/mic audio, physical PTT,
and a dedicated SOS button), so it is supported experimentally as a
budget tier.

With a budget device come **budget capabilities and hardware
limitations**:

- **Lower microphone gain and audio fidelity** compared to AINA V1/V2
  speakermics. XVoice applies a `+5 dB` (`1.78x`) software mic-gain boost
  automatically when capturing over BT02 Bluetooth SCO, but acoustic
  clarity and wind/noise rejection remain below AINA hardware.
- **Emergency (SOS) button ONLY triggers — it CANNOT disable/disarm an
  emergency:** The BT02 firmware requires a **2-second press-and-hold**
  before transmitting its emergency event (`0x08`) and **never sends a
  release event**. Because of this hardware limitation, holding SOS for
  2 seconds **only fires** ATAK's emergency beacon — it **does not**
  cancel or turn it off. **If you trigger an emergency with this budget
  speaker-mic, you MUST disable/disarm it from the ATAK app** (Emergency
  / Alert tool). By contrast, on **AINA PTT Voice Responders (V1 and
  V2)**, a 1-second long press on the speaker-mic disables / turns off
  an active emergency directly.
- **Top / group switch button is unmapped.** Only the primary PTT and
  SOS buttons are mapped.
- **No secondary channel (VS2) button.**

## Pairing (one-step Classic Bluetooth)

Pair the BT02 in Android Bluetooth Settings just like an AINA
speaker-mic — **do not** use XV's "Scan for BLE PTT device" button:

1. Power the Inrico BT02 **completely off**.
2. **Press and hold the Power button** (~5–6 seconds) until the LED
   flashes **red and blue alternating** (Classic pairing mode).
3. Open **Android Settings → Connected devices → Pair new device** and
   pair the **`B02PTT-xxxx`** Classic Bluetooth audio entry (with
   Headset / Call audio).
   - *Note:* The BT02 also advertises an unbonded BLE peripheral with
     the same `B02PTT-xxxx` name. If Android hangs while pairing, make
     sure the LED is flashing red/blue (Classic pairing mode) and you
     are pairing the Classic audio entry rather than the BLE-only
     advertisement.
4. Open ATAK / XVoice. `B02PTT-xxxx` appears in the **Primary
   Speaker-Mic** picker just like an AINA. When selected (or
   auto-connected), XVoice routes HFP audio to the Classic device and
   automatically discovers and connects to the companion BLE button link
   in the background.

## Button mapping

| Physical button | Action | Notes |
| --- | --- | --- |
| **PTT** (side) | Transmit on primary channel | Press and hold to talk; release to stop. |
| **SOS / Emergency** | Fire ATAK emergency beacon | Press and hold **2 seconds** to fire. **Fire-only** — cancel active emergency in ATAK's Emergency tool. |
| **Top / group button** | Unmapped | Intentionally ignored. |
| **Volume +/-** | Local speaker volume | Handled on the speaker-mic hardware. |
