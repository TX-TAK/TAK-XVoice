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
        XvAudioModeClaims.release(XvAudioModeClaims.SCO_LINK)
        assertTrue(XvAudioModeClaims.held())
        XvAudioModeClaims.release(XvAudioModeClaims.AUDIO_PLANT)
        assertFalse(XvAudioModeClaims.held())
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
