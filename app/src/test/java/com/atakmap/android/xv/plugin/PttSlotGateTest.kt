package com.atakmap.android.xv.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSlotGateTest {
    @Test
    fun `channel 2 on - PTT2 follows channel 2`() {
        assertTrue(PttSlotGate.canSpeakSlot1(channel2On = true, slot0Suppressed = true, slot1Suppressed = false))
        assertFalse(PttSlotGate.canSpeakSlot1(channel2On = true, slot0Suppressed = false, slot1Suppressed = true))
    }

    @Test
    fun `cleared listen-only channel 2 no longer bonks PTT2`() {
        // Channel 2 was listen-only, then turned off: PTT2 keys channel 1.
        assertTrue(PttSlotGate.canSpeakSlot1(channel2On = false, slot0Suppressed = false, slot1Suppressed = true))
    }

    @Test
    fun `channel 2 off - PTT2 obeys channel 1's listen-only`() {
        assertFalse(PttSlotGate.canSpeakSlot1(channel2On = false, slot0Suppressed = true, slot1Suppressed = false))
    }
}
