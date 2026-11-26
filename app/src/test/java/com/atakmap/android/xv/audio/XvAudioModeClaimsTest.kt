package com.atakmap.android.xv.audio

import android.media.AudioManager
import com.atakmap.android.xv.service.ExternalCallDetector
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XvAudioModeClaimsTest {
    @After
    fun tearDown() = XvAudioModeClaims.clearForTest()

    @Test
    fun `held while any component claims, released per tag`() {
        assertFalse(XvAudioModeClaims.held())
        XvAudioModeClaims.claim(XvAudioModeClaims.AUDIO_PLANT)
        XvAudioModeClaims.claim(XvAudioModeClaims.SCO_LINK)
        XvAudioModeClaims.release(XvAudioModeClaims.SCO_LINK, nowMs = 1_000L)
        assertTrue(XvAudioModeClaims.held())
        XvAudioModeClaims.release(XvAudioModeClaims.AUDIO_PLANT, nowMs = 1_000L)
        assertFalse(XvAudioModeClaims.held())
    }

    @Test
    fun `mode stays XV's for the release grace, then not`() {
        XvAudioModeClaims.claim(XvAudioModeClaims.AUDIO_PLANT)
        XvAudioModeClaims.release(XvAudioModeClaims.AUDIO_PLANT, nowMs = 10_000L)
        assertTrue(XvAudioModeClaims.heldOrRecentlyReleased(nowMs = 10_000L + XvAudioModeClaims.RELEASE_GRACE_MS))
        assertFalse(XvAudioModeClaims.heldOrRecentlyReleased(nowMs = 10_001L + XvAudioModeClaims.RELEASE_GRACE_MS))
    }

    @Test
    fun `releasing one of two claims does not start the grace`() {
        XvAudioModeClaims.claim(XvAudioModeClaims.AUDIO_PLANT)
        XvAudioModeClaims.claim(XvAudioModeClaims.SCO_LINK)
        XvAudioModeClaims.release(XvAudioModeClaims.SCO_LINK, nowMs = 1_000L)
        XvAudioModeClaims.release(XvAudioModeClaims.AUDIO_PLANT, nowMs = 50_000L)
        assertTrue(XvAudioModeClaims.heldOrRecentlyReleased(nowMs = 51_000L))
    }

    @Test
    fun `XV-claimed IN_COMMUNICATION is not latched as an external call`() {
        // Field repro: the RX plant sets IN_COMMUNICATION before any audio
        // has played, then the SCO link samples the detector.
        val d = ExternalCallDetector()
        d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false)
        XvAudioModeClaims.claim(XvAudioModeClaims.AUDIO_PLANT)
        assertFalse(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = XvAudioModeClaims.held()))
        assertFalse(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = XvAudioModeClaims.held()))
    }
}
