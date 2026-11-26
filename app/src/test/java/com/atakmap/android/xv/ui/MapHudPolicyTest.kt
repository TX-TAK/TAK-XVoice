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
        assertEquals("TEXAS TAK TEAM", MapHudPolicy.labelName(" Texas TAK Team "))
        assertEquals("—", MapHudPolicy.labelName(null))
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

    // ---- size ----

    @Test
    fun `sizes are a share of the short side`() {
        // 640dp tablet short side: 8% / 10% / 12%.
        assertEquals(51.2f, MapHudPolicy.hexWidthDp(MapHudPolicy.Size.SMALL, 640f), 0.01f)
        assertEquals(64f, MapHudPolicy.hexWidthDp(MapHudPolicy.Size.MEDIUM, 640f), 0.01f)
        assertEquals(76.8f, MapHudPolicy.hexWidthDp(MapHudPolicy.Size.LARGE, 640f), 0.01f)
    }

    @Test
    fun `size never drops below a gloved touch target or blows up`() {
        assertEquals(48f, MapHudPolicy.hexWidthDp(MapHudPolicy.Size.SMALL, 360f), 0.01f)
        assertEquals(96f, MapHudPolicy.hexWidthDp(MapHudPolicy.Size.LARGE, 2000f), 0.01f)
    }

    @Test
    fun `unknown saved size falls back to medium`() {
        assertEquals(MapHudPolicy.Size.MEDIUM, MapHudPolicy.Size.parse(null))
        assertEquals(MapHudPolicy.Size.MEDIUM, MapHudPolicy.Size.parse("huge"))
        assertEquals(MapHudPolicy.Size.LARGE, MapHudPolicy.Size.parse("large"))
    }

    // ---- press feedback ----

    @Test
    fun `held button is neutral PRESSED until the service confirms TX`() {
        assertEquals(Look.PRESSED, MapHudPolicy.button(slot(0), held = true).look)
        assertEquals(listOf(Look.IDLE, Look.PRESSED), MapHudPolicy.buttons(slot(0), slot(1), heldSlot = 1).map { it.look })
    }

    @Test
    fun `confirmed TX is red with TX label`() {
        val b = MapHudPolicy.button(slot(0).copy(transmitting = true), held = true)
        assertEquals(Look.TRANSMITTING, b.look)
        assertEquals("TX", b.label)
    }

    @Test
    fun `refused press shows DENIED on that slot only`() {
        val drawn = MapHudPolicy.buttons(slot(0), slot(1), heldSlot = 1, deniedSlot = 1)
        assertEquals(listOf(Look.IDLE, Look.DENIED), drawn.map { it.look })
        assertEquals("DENIED", drawn[1].label)
    }

    @Test
    fun `talker pill shows both channels when both are talking`() {
        val pill =
            MapHudPolicy.talkerPill(
                slot(0).copy(talkers = listOf("Bravo-2")),
                slot(1).copy(talkers = listOf("Charlie-1")),
            )!!
        assertTrue(pill.contains("1 · Bravo-2"))
        assertTrue(pill.contains("2 · Charlie-1"))
    }

    // ---- long names ----

    // Fake text measure: 6px per character per sp-unit of 10.
    private val measure: (String, Float) -> Float = { t, sp -> t.length * 6f * sp / 10f }

    @Test
    fun `short name fits on one line at the largest size`() {
        val l = MapHudPolicy.layoutLabel("ALPHA", maxWidth = 60f, measure = measure)!!
        assertEquals(listOf("ALPHA"), l.lines)
        assertEquals(13f, l.sizeSp, 0.01f)
    }

    @Test
    fun `two-word name wraps to two lines instead of truncating`() {
        val l = MapHudPolicy.layoutLabel("TEXAS TAK TEAM", maxWidth = 60f, measure = measure)!!
        assertEquals(2, l.lines.size)
        assertEquals("TEXAS TAK TEAM", l.lines.joinToString(" "))
    }

    @Test
    fun `too long for two lines falls back to scrolling`() {
        assertNull(MapHudPolicy.layoutLabel("ALPHA BRAVO CHARLIE DELTA", maxWidth = 50f, measure = measure))
        assertNull(MapHudPolicy.layoutLabel("SUPERCALIFRAGILISTIC", maxWidth = 50f, measure = measure))
    }

    @Test
    fun `fitChars counts the leading characters that fit`() {
        assertEquals(5, MapHudPolicy.fitChars("ABCDEFGH", 30f) { it.length * 6f })
        assertEquals(1, MapHudPolicy.fitChars("ABC", 1f) { it.length * 6f })
    }

    @Test
    fun `scroll rests at start, steps one char per tick, rests at end, repeats`() {
        val label = "ABCDEFG" // window 4 → travel 3
        val frames = (0L until 9L).map { MapHudPolicy.scrollWindow(label, 4, it) }
        assertEquals(listOf("ABCD", "ABCD", "ABCD", "ABCD", "BCDE", "CDEF", "DEFG", "DEFG", "DEFG"), frames)
        assertEquals("ABCD", MapHudPolicy.scrollWindow(label, 4, 9L)) // cycle = 3 + 2*3
    }

    @Test
    fun `scroll window wider than the name shows it whole`() {
        assertEquals("ABC", MapHudPolicy.scrollWindow("ABC", 5, 7L))
    }

    @Test
    fun `labelName shows a dash for a missing or blank channel`() {
        assertEquals("—", MapHudPolicy.labelName(null))
        assertEquals("—", MapHudPolicy.labelName("   "))
        assertEquals("ALPHA", MapHudPolicy.labelName(" alpha "))
    }

    @Test
    fun `fitChars falls back to one character when nothing fits`() {
        assertEquals(1, MapHudPolicy.fitChars("ALPHA", maxWidth = 1f) { it.length * 10f })
        assertEquals(3, MapHudPolicy.fitChars("ALPHA", maxWidth = 30f) { it.length * 10f })
    }
}
