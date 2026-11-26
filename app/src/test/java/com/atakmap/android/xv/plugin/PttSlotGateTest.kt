package com.atakmap.android.xv.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSlotGateTest {
    private fun gate(
        on: Boolean,
        live: Boolean = true,
        s0: Boolean = false,
        s1: Boolean = false,
    ) = PttSlotGate.canSpeakSlot1(channel2On = on, channel2Live = live, slot0Suppressed = s0, slot1Suppressed = s1)

    @Test
    fun `channel 2 on - PTT2 follows channel 2`() {
        assertTrue(gate(on = true, s0 = true))
        assertFalse(gate(on = true, s1 = true))
    }

    @Test
    fun `channel 2 configured but not live refuses PTT2`() {
        // Must not fall through to channel 1: that would key the wrong net.
        assertFalse(gate(on = true, live = false))
        assertFalse(gate(on = true, live = false, s0 = false, s1 = false))
    }

    @Test
    fun `cleared listen-only channel 2 no longer bonks PTT2`() {
        // Channel 2 was listen-only, then turned off: PTT2 keys channel 1.
        assertTrue(gate(on = false, live = false, s1 = true))
    }

    @Test
    fun `channel 2 off - PTT2 obeys channel 1's listen-only`() {
        assertFalse(gate(on = false, s0 = true))
    }
}
