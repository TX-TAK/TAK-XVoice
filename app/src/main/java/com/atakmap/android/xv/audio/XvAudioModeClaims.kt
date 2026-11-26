package com.atakmap.android.xv.audio

import java.util.concurrent.ConcurrentHashMap

/**
 * Who in XV currently holds the device in MODE_IN_COMMUNICATION.
 *
 * The external-call detector reads the system audio mode, which can't
 * say who set it. Without this record, XV's own RX/TX plant and SCO link
 * putting the device into IN_COMMUNICATION looked like somebody else's
 * call: the SCO link refused to start ("external call holds the audio
 * path") and channel RX was dropped as "phone call active" while
 * bridging (field report 2026-11-26, Tab Active5 + Moto G Power).
 *
 * Each XV component that sets the mode claims its tag BEFORE the set and
 * releases it AFTER restoring, so a sample taken in between always sees
 * the claim. Process-local: every XV setMode call runs in the voice
 * service process, the same process as the detector.
 */
object XvAudioModeClaims {
    const val AUDIO_PLANT = "audio-plant"
    const val SCO_LINK = "sco-link"

    private val holders = ConcurrentHashMap.newKeySet<String>()

    fun claim(tag: String) {
        holders.add(tag)
    }

    fun release(tag: String) {
        holders.remove(tag)
    }

    /** True while any XV component holds IN_COMMUNICATION. */
    fun held(): Boolean = holders.isNotEmpty()

    /** Test-only: forget every claim. */
    internal fun clearForTest() {
        holders.clear()
    }
}
