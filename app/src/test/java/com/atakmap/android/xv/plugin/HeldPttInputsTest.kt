package com.atakmap.android.xv.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldPttInputsTest {
    private var clock = 0L
    private val held = HeldPttInputs(maxAgeMs = 1_000L, now = { clock })

    @Test
    fun `down then up on the same reader and slot clears`() {
        held.onInput(true, 0, "AINA_V2@1")
        assertTrue(held.anyHeld())
        held.onInput(false, 0, "AINA_V2@1")
        assertFalse(held.anyHeld())
    }

    @Test
    fun `another reader's up edge does not clear a held key`() {
        held.onInput(true, 0, "AINA_V2@1")
        held.onInput(false, 0, "AINA_V2@2")
        assertTrue(held.anyHeld())
    }

    @Test
    fun `reader retirement releases all its slots`() {
        held.onInput(true, 0, "INRICO_BT02@7")
        held.onInput(true, 1, "INRICO_BT02@7")
        held.onInput(false, -1, "*@7")
        assertFalse(held.anyHeld())
    }

    @Test
    fun `forgetSource releases every reader of that source`() {
        held.onInput(true, 0, "PRYME_BLE@3")
        held.onInput(true, 1, "PRYME_BLE@4")
        held.onInput(true, 0, "DEFAULT")
        held.onInput(false, -1, "PRYME_BLE")
        assertTrue(held.anyHeld()) // on-screen still held
        held.onInput(false, 0, "DEFAULT")
        assertFalse(held.anyHeld())
    }

    @Test
    fun `burst end clears the slot`() {
        held.onInput(true, 1, "AINA_V1@5")
        held.onBurstEnded(1)
        assertFalse(held.anyHeld())
    }

    @Test
    fun `stale held input ages out instead of blocking swap all session`() {
        held.onInput(true, 0, "AINA_V2@1") // up edge lost
        clock = 999L
        assertTrue(held.anyHeld())
        clock = 1_001L
        assertFalse(held.anyHeld())
    }

    @Test
    fun `service link reset clears everything`() {
        held.onInput(true, 0, "AINA_V2@1")
        held.clear()
        assertFalse(held.anyHeld())
    }
}
