package com.atakmap.android.xv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnScreenPttGateTest {
    @Test
    fun `press then release returns the pressed slot`() {
        val g = OnScreenPttGate()
        assertTrue(g.down(1))
        assertEquals(1, g.release(1))
        assertNull(g.heldSlot)
    }

    @Test
    fun `brushing the other button while one is held keeps the held one keyed`() {
        val g = OnScreenPttGate()
        assertTrue(g.down(0))
        assertFalse(g.down(1)) // second finger on PTT2: ignored
        assertNull(g.release(1)) // its UP does not end PTT1
        assertEquals(0, g.heldSlot)
        assertEquals(0, g.release(0))
    }

    @Test
    fun `panel hide or disable releases whatever is held, once`() {
        val g = OnScreenPttGate()
        g.down(1)
        assertEquals(1, g.releaseAny())
        assertNull(g.releaseAny())
        assertNull(g.release(1)) // late UP after the forced release is a no-op
    }

    @Test
    fun `slide-off triggers only past the touch slop`() {
        assertFalse(OnScreenPttGate.slidOff(x = 50f, y = 30f, width = 100, height = 60, slopPx = 8f))
        assertFalse(OnScreenPttGate.slidOff(x = 105f, y = 30f, width = 100, height = 60, slopPx = 8f))
        assertTrue(OnScreenPttGate.slidOff(x = 109f, y = 30f, width = 100, height = 60, slopPx = 8f))
        assertTrue(OnScreenPttGate.slidOff(x = 50f, y = -9f, width = 100, height = 60, slopPx = 8f))
    }
}
