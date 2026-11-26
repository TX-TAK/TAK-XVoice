package com.atakmap.android.xv.audio

/**
 * "Low latency mode" (internally "hot mic"; the pref key, AIDL method and
 * TxController names keep that name). With a Bluetooth speaker-mic or
 * headset as the voice route, XV keeps the headset's voice (call-audio)
 * link open so a press skips the Bluetooth start-up delay. It is NOT an
 * open mic: nothing is sent unless PTT is pressed. No effect on the phone
 * speaker, earpiece or wired routes.
 *
 * Operator decision: the link is held the whole time you're in a channel
 * ([HOLD_MS] = [WHOLE_CHANNEL]). It's released when you leave every
 * channel, turn the mode off, the route stops being Bluetooth, or a phone
 * call takes the link — and re-armed when those clear. The hold is one
 * constant: set a finite value to hold only for that long after joining /
 * each transmission ([holdSentence] follows).
 */
object LowLatencyMode {
    /** Hold for as long as the operator is in a channel. */
    const val WHOLE_CHANNEL = Long.MAX_VALUE

    /** How long the voice link stays open after joining / the last TX. */
    const val HOLD_MS = WHOLE_CHANNEL

    /** "1 minute", "2 minutes", "30 seconds" for a finite hold. */
    fun holdPhrase(holdMs: Long = HOLD_MS): String {
        val s = holdMs / 1000
        return when {
            s % 60 == 0L && s >= 60 -> if (s == 60L) "1 minute" else "${s / 60} minutes"
            else -> "$s seconds"
        }
    }

    /** How long the link is open, as Settings copy. */
    fun holdSentence(holdMs: Long = HOLD_MS): String =
        if (holdMs == WHOLE_CHANNEL) {
            "the whole time you're in a channel"
        } else {
            "for ${holdPhrase(holdMs)} after you join a channel or talk"
        }
}
