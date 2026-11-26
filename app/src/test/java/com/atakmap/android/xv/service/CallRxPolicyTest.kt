package com.atakmap.android.xv.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRxPolicyTest {
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
