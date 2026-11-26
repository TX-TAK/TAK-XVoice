package com.atakmap.android.xv.service

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRxPolicyTest {
    @Test
    fun `ringing and cellular calls are phone calls`() {
        assertTrue(CallRxPolicy.phoneCallActive(AudioManager.MODE_RINGTONE))
        assertTrue(CallRxPolicy.phoneCallActive(AudioManager.MODE_IN_CALL))
        assertTrue(CallRxPolicy.phoneCallActive(AudioManager.MODE_CALL_SCREENING))
    }

    @Test
    fun `normal and XV's own communication mode are not`() {
        assertFalse(CallRxPolicy.phoneCallActive(AudioManager.MODE_NORMAL))
        assertFalse(CallRxPolicy.phoneCallActive(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `phone call drops RX on both channels`() {
        assertTrue(CallRxPolicy.dropChannelRx(0, phoneCall = true, xvDirectCall = false))
        assertTrue(CallRxPolicy.dropChannelRx(1, phoneCall = true, xvDirectCall = false))
    }

    @Test
    fun `XV direct call keeps the call slot and drops channel 2`() {
        assertFalse(CallRxPolicy.dropChannelRx(0, phoneCall = false, xvDirectCall = true))
        assertTrue(CallRxPolicy.dropChannelRx(1, phoneCall = false, xvDirectCall = true))
    }

    @Test
    fun `no call plays everything`() {
        assertFalse(CallRxPolicy.dropChannelRx(0, phoneCall = false, xvDirectCall = false))
        assertFalse(CallRxPolicy.dropChannelRx(1, phoneCall = false, xvDirectCall = false))
    }
}
