package com.atakmap.android.xv.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapSpeakGateTest {
    private val pushed = mutableListOf<Pair<Int, Boolean>>()
    private val gate = SwapSpeakGate { slot, can -> pushed += slot to can }

    // What the service ends up with per slot.
    private fun service(): Map<Int, Boolean> = pushed.groupBy { it.first }.mapValues { it.value.last().second }

    @Test
    fun `close shuts both slots, reopen restores the verdicts`() {
        gate.close()
        assertEquals(mapOf(0 to false, 1 to false), service())
        gate.reopen(slot0 = true, slot1 = false)
        assertEquals(mapOf(0 to true, 1 to false), service())
        assertFalse(gate.closed)
    }

    @Test
    fun `suppress update during a swap is held until reopen`() {
        gate.close()
        assertFalse(gate.pushIfOpen(0, true))
        assertEquals(mapOf(0 to false, 1 to false), service())
        gate.reopen(slot0 = false, slot1 = true) // caller re-reads the recorded state
        assertEquals(mapOf(0 to false, 1 to true), service())
    }

    @Test
    fun `early ack - outcome reopen before the swap call returns leaves the gate open`() {
        // Order the plugin now uses: close, then start; the outcome can
        // land before swapChannels returns. Nothing closes afterwards.
        gate.close()
        gate.reopen(slot0 = true, slot1 = true) // fast outcome
        assertEquals(mapOf(0 to true, 1 to true), service())
        assertTrue(gate.pushIfOpen(1, true))
    }

    @Test
    fun `refused swap reopens immediately`() {
        gate.close()
        gate.reopen(slot0 = true, slot1 = true)
        assertFalse(gate.closed)
    }
}
