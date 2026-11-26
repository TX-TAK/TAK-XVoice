package com.atakmap.android.xv.service

/**
 * Operator rule: during any phone call, channel RX is ignored — not
 * played and not buffered. (Replaying what was missed is a separate
 * backlog item; nothing here keeps audio.)
 *
 * "Phone call" is read from the system audio mode: ringing, in a
 * cellular call, or the call-screening / redirect modes. XV's own
 * Telecom call (placed around every TX and for direct calls) runs in
 * MODE_IN_COMMUNICATION, which is deliberately NOT treated as a phone
 * call here — so are other apps' VoIP calls, which the audio mode can't
 * tell apart from XV's own audio.
 *
 * During an XV direct call, the call itself rides channel 1's session
 * (moved to the call's temporary channel), so only channel 2's RX is
 * dropped.
 */
object CallRxPolicy {
    // AudioManager.MODE_* values, inlined so the policy is pure.
    private const val MODE_RINGTONE = 1
    private const val MODE_IN_CALL = 2
    private const val MODE_CALL_SCREENING = 4
    private const val MODE_CALL_REDIRECT = 5

    fun phoneCallActive(audioMode: Int): Boolean =
        audioMode == MODE_RINGTONE ||
            audioMode == MODE_IN_CALL ||
            audioMode == MODE_CALL_SCREENING ||
            audioMode == MODE_CALL_REDIRECT

    fun dropChannelRx(
        slot: Int,
        phoneCall: Boolean,
        xvDirectCall: Boolean,
    ): Boolean = phoneCall || (xvDirectCall && slot != 0)
}
