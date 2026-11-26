package com.atakmap.android.xv.ui

import com.atakmap.android.xv.ui.MapHudPolicy.Look
import com.atakmap.android.xv.ui.MapHudPolicy.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapHudPolicyTest {
    // Live, idle slot on its default channel; tests vary it with copy().
    private fun slot(n: Int) = SlotState(n, if (n == 0) "Alpha" else "Command", true, false, false, emptyList())

    @Test
    fun `shown while the panel is collapsed, hidden while open`() {
        assertTrue(MapHudPolicy.visible(enabled = true, panelOpen = false))
        assertFalse(MapHudPolicy.visible(enabled = true, panelOpen = true))
    }

    @Test
    fun `toggle off hides it everywhere`() {
        assertFalse(MapHudPolicy.visible(enabled = false, panelOpen = false))
    }

    @Test
    fun `left-handed docks on the left`() {
        assertEquals(MapHudPolicy.Side.LEFT, MapHudPolicy.side(leftHanded = true))
        assertEquals(MapHudPolicy.Side.RIGHT, MapHudPolicy.side(leftHanded = false))
    }

    @Test
    fun `button 2 only while channel 2 is on`() {
        assertEquals(listOf(0), MapHudPolicy.buttons(slot(0), null).map { it.slot })
        assertEquals(listOf(0, 1), MapHudPolicy.buttons(slot(0), slot(1)).map { it.slot })
    }

    @Test
    fun `looks follow tx, rx, listen-only and live`() {
        assertEquals(Look.IDLE, MapHudPolicy.button(slot(0)).look)
        assertEquals(Look.RECEIVING, MapHudPolicy.button(slot(0).copy(talkers = listOf("Bravo-2"))).look)
        assertEquals(Look.TRANSMITTING, MapHudPolicy.button(slot(0).copy(transmitting = true, talkers = listOf("x"))).look)
        assertEquals(Look.UNAVAILABLE, MapHudPolicy.button(slot(0).copy(listenOnly = true)).look)
        assertEquals(Look.UNAVAILABLE, MapHudPolicy.button(slot(1).copy(live = false)).look)
    }

    @Test
    fun `label is the short channel name, TX while transmitting`() {
        assertEquals("ALPHA", MapHudPolicy.button(slot(0)).label)
        assertEquals("TX", MapHudPolicy.button(slot(0).copy(transmitting = true)).label)
        assertEquals("LOGIST…", MapHudPolicy.shortName("Logistics"))
        assertEquals("—", MapHudPolicy.shortName(null))
    }

    @Test
    fun `talker pill names the slot and talker`() {
        assertEquals("1 · Bravo-2", MapHudPolicy.talkerPill(slot(0).copy(talkers = listOf("Bravo-2")), slot(1)))
        assertEquals("2 · Charlie-1", MapHudPolicy.talkerPill(slot(0), slot(1).copy(talkers = listOf("Charlie-1"))))
        assertNull(MapHudPolicy.talkerPill(slot(0), null))
    }

    @Test
    fun `held button is released when the overlay hides`() {
        assertEquals(0, MapHudPolicy.releaseOnChange(heldSlot = 0, nowVisible = false, drawnSlots = setOf(0, 1)))
    }

    @Test
    fun `held button 2 is released when channel 2 turns off`() {
        assertEquals(1, MapHudPolicy.releaseOnChange(heldSlot = 1, nowVisible = true, drawnSlots = setOf(0)))
    }

    @Test
    fun `nothing to release while still drawn or not held`() {
        assertNull(MapHudPolicy.releaseOnChange(heldSlot = 0, nowVisible = true, drawnSlots = setOf(0)))
        assertNull(MapHudPolicy.releaseOnChange(heldSlot = null, nowVisible = false, drawnSlots = emptySet()))
    }
}
