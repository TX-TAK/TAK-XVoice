package com.atakmap.android.xv.service

/**
 * The single "is somebody else's call up?" signal. Drives all three call
 * behaviours so they can't disagree:
 *  - the PTT cellular gate (on-screen PTT refused, see PttCellularGate),
 *  - the channel-RX drop (operator rule: no channel audio during any
 *    phone call — not played, not kept; see CallRxPolicy),
 *  - the panel's "On a phone call · channel audio paused" bar.
 *
 * Read from the system audio mode, which is ambiguous for
 * MODE_IN_COMMUNICATION: XV's own Telecom call / RX playback uses it,
 * and so do VoLTE cellular calls (answered or placed) and other apps'
 * VoIP calls. So the decision is LATCHED on how the mode was entered:
 *  - ringing / in-call / call-screening / redirect: a call, always;
 *  - IN_COMMUNICATION entered from a call mode stays a call (an answered
 *    VoLTE call goes RINGTONE then IN_COMMUNICATION);
 *  - IN_COMMUNICATION entered while XV wasn't producing audio is
 *    someone else's call (placed VoLTE, another VoIP app), and stays so
 *    until the mode leaves IN_COMMUNICATION;
 *  - IN_COMMUNICATION entered by XV's own audio stays XV's;
 *  - any other mode ends the call.
 * Limitation: a VoIP call another app starts while XV already holds
 * IN_COMMUNICATION can't be seen (the mode doesn't change).
 *
 * The caller must not count audio it dropped because of this signal as
 * "XV's own audio" — otherwise a call would un-latch itself.
 */
class ExternalCallDetector(
    /**
     * False on devices with no telephony stack (Wi-Fi-only tablets). They
     * cannot place or receive a cellular call, so IN_COMMUNICATION there is
     * never a VoLTE call; latching it as one only produced false
     * "phone call" states (tablet field report, 2026-11-26). Ringing and
     * legacy in-call modes still count as calls on any device.
     */
    @Volatile
    var telephonyAvailable: Boolean = true,
) {
    private var lastMode: Int? = null

    @Volatile
    var active: Boolean = false
        private set

    /**
     * Feed the current audio mode. [xvOwnAudio] = XV's Telecom call, its
     * post-call grace, or RX XV actually played is holding the mode now.
     */
    @Synchronized
    fun sample(
        audioMode: Int,
        xvOwnAudio: Boolean,
    ): Boolean {
        active =
            when (audioMode) {
                MODE_RINGTONE, MODE_IN_CALL, MODE_CALL_SCREENING, MODE_CALL_REDIRECT, MODE_COMMUNICATION_REDIRECT -> true
                MODE_IN_COMMUNICATION ->
                    telephonyAvailable &&
                        (active || (lastMode != MODE_IN_COMMUNICATION && !xvOwnAudio))
                else -> false
            }
        lastMode = audioMode
        return active
    }

    /** True while the call is ringing (vs. connected). */
    @Synchronized
    fun ringing(): Boolean = active && lastMode == MODE_RINGTONE

    companion object {
        // AudioManager.MODE_* values, inlined so this stays pure.
        const val MODE_RINGTONE = 1
        const val MODE_IN_CALL = 2
        const val MODE_IN_COMMUNICATION = 3
        const val MODE_CALL_SCREENING = 4
        const val MODE_CALL_REDIRECT = 5
        const val MODE_COMMUNICATION_REDIRECT = 6
    }
}
