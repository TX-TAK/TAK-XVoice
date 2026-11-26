package com.atakmap.android.xv.audio

/**
 * "Low latency mode" (internally "hot mic"; the pref key, AIDL method and
 * TxController names keep that name). With a Bluetooth speaker-mic or
 * headset as the voice route, XV keeps the headset's voice (call-audio)
 * link open for [HOLD_MS] after you join a channel and after each
 * transmission, so a press inside that window skips the Bluetooth
 * start-up delay. It is NOT an open mic: nothing is sent unless PTT is
 * pressed. No effect on the phone speaker, earpiece or wired routes.
 *
 * The hold is one constant so a policy change (e.g. "the whole time in a
 * channel") is a one-line change here plus the copy in [holdPhrase].
 */
object LowLatencyMode {
    /** How long the voice link stays open after joining / the last TX. */
    const val HOLD_MS = 60_000L

    /** "1 minute", "2 minutes", "30 seconds" — for the Settings copy. */
    fun holdPhrase(holdMs: Long = HOLD_MS): String {
        val s = holdMs / 1000
        return when {
            s % 60 == 0L && s >= 60 -> if (s == 60L) "1 minute" else "${s / 60} minutes"
            else -> "$s seconds"
        }
    }
}
