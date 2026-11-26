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

    /**
     * After the last release the reported mode can lag the restore
     * (~1.4 s observed on Pixel / API 35), so XV's mode counts as XV's
     * for this long after it lets go.
     */
    const val RELEASE_GRACE_MS = 2_000L

    private val holders = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastReleaseElapsedMs = 0L

    fun claim(tag: String) {
        holders.add(tag)
    }

    fun release(
        tag: String,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        if (holders.remove(tag) && holders.isEmpty()) lastReleaseElapsedMs = nowMs
    }

    /** True while any XV component holds IN_COMMUNICATION. */
    fun held(): Boolean = holders.isNotEmpty()

    /** [held], or the last claim ended within [RELEASE_GRACE_MS]. */
    fun heldOrRecentlyReleased(nowMs: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        held() || (lastReleaseElapsedMs > 0L && nowMs - lastReleaseElapsedMs <= RELEASE_GRACE_MS)

    /** Test-only: forget every claim. */
    internal fun clearForTest() {
        holders.clear()
        lastReleaseElapsedMs = 0L
    }
}
