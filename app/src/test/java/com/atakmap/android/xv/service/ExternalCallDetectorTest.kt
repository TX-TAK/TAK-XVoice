package com.atakmap.android.xv.service

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCallDetectorTest {
    private val d = ExternalCallDetector()

    @Test
    fun `answered VoLTE call stays a call after switching to IN_COMMUNICATION`() {
        assertFalse(d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false))
        assertTrue(d.sample(AudioManager.MODE_RINGTONE, xvOwnAudio = false))
        assertTrue(d.ringing())
        // Answered: VoLTE uses IN_COMMUNICATION, not IN_CALL.
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
        assertFalse(d.ringing())
        // XV audio reported mid-call doesn't un-latch it.
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = true))
        assertFalse(d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false))
    }

    @Test
    fun `placed VoLTE or another VoIP call is a call until the mode leaves`() {
        d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false)
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = true))
        assertFalse(d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false))
    }

    @Test
    fun `XV-entered IN_COMMUNICATION is not a call, even after XV audio stops`() {
        d.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false)
        assertFalse(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = true))
        // Grace / RX window lapsed but the mode never left: still XV.
        assertFalse(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
    }

    @Test
    fun `a cellular ring during XV audio is a call`() {
        d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = true)
        assertTrue(d.sample(AudioManager.MODE_RINGTONE, xvOwnAudio = true))
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = true))
    }

    @Test
    fun `legacy in-call and call screening are calls`() {
        assertTrue(d.sample(AudioManager.MODE_IN_CALL, xvOwnAudio = true))
        assertTrue(d.sample(AudioManager.MODE_CALL_SCREENING, xvOwnAudio = false))
    }

    @Test
    fun `first sample in IN_COMMUNICATION with no XV audio is a call`() {
        assertTrue(d.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
    }

    @Test
    fun `IN_COMMUNICATION is never a call on a device without telephony`() {
        val tablet = ExternalCallDetector(telephonyAvailable = false)
        tablet.sample(AudioManager.MODE_NORMAL, xvOwnAudio = false)
        assertFalse(tablet.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
        assertFalse(tablet.sample(AudioManager.MODE_IN_COMMUNICATION, xvOwnAudio = false))
        // Real call modes still count even on a tablet.
        assertTrue(tablet.sample(AudioManager.MODE_RINGTONE, xvOwnAudio = false))
        assertTrue(tablet.sample(AudioManager.MODE_IN_CALL, xvOwnAudio = false))
    }

    @Test
    fun `mode constants match AudioManager`() {
        assertEquals(AudioManager.MODE_RINGTONE, ExternalCallDetector.MODE_RINGTONE)
        assertEquals(AudioManager.MODE_IN_CALL, ExternalCallDetector.MODE_IN_CALL)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, ExternalCallDetector.MODE_IN_COMMUNICATION)
        assertEquals(AudioManager.MODE_CALL_SCREENING, ExternalCallDetector.MODE_CALL_SCREENING)
    }
}
