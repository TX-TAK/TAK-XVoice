package com.atakmap.android.xv.ui

import com.atakmap.android.xv.ui.ChannelListPolicy.Access
import com.atakmap.android.xv.ui.ChannelListPolicy.Action
import com.atakmap.android.xv.ui.ChannelListPolicy.Channel
import com.atakmap.android.xv.ui.ChannelListPolicy.Pill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListPolicyTest {
    private val lobby = Channel(0, "Lobby", -1, Access.FULL)
    private val alpha = Channel(1, "Alpha", 0, Access.FULL)
    private val alphaTeam1 = Channel(2, "Alpha / Team 1", 1, Access.FULL)
    private val alphaTeam2 = Channel(3, "Alpha / Team 2", 1, Access.FULL)
    private val command = Channel(4, "Command", 0, Access.FULL)
    private val logistics = Channel(5, "Logistics", 0, Access.LISTEN)
    private val restricted = Channel(6, "Bravo", 0, Access.NONE)
    private val adHoc = Channel(-1, "Ad-hoc 1", -1, Access.FULL, offline = true)

    private val all = listOf(restricted, command, alphaTeam2, adHoc, logistics, alphaTeam1, lobby, alpha)

    // ---- ordering ----

    @Test
    fun `lobby first, subchannels under parent, tiers then names, offline last`() {
        val rows = ChannelListPolicy.order(all)
        assertEquals(
            listOf("Lobby", "Alpha", "Alpha / Team 1", "Alpha / Team 2", "Command", "Logistics", "Bravo", "Ad-hoc 1"),
            rows.map { it.channel.name },
        )
        assertEquals(listOf(0, 0, 1, 1, 0, 0, 0, 0), rows.map { it.depth })
    }

    @Test
    fun `orphaned subchannel is shown at top level`() {
        val orphan = Channel(9, "Orphan", 42, Access.FULL)
        val rows = ChannelListPolicy.order(listOf(lobby, orphan))
        assertEquals(listOf("Lobby", "Orphan"), rows.map { it.channel.name })
        assertEquals(0, rows[1].depth)
    }

    @Test
    fun `parent cycle does not loop forever`() {
        val a = Channel(10, "A", 11, Access.FULL)
        val b = Channel(11, "B", 10, Access.FULL)
        // Neither is reachable from Root, so neither is listed — but it terminates.
        val rows = ChannelListPolicy.order(listOf(lobby, a, b))
        assertEquals("Lobby", rows.first().channel.name)
    }

    @Test
    fun `search flattens matches and is case-insensitive`() {
        val rows = ChannelListPolicy.filter(ChannelListPolicy.order(all), "team")
        assertEquals(listOf("Alpha / Team 1", "Alpha / Team 2"), rows.map { it.channel.name })
        assertTrue(rows.all { it.depth == 0 })
        assertEquals(all.size, ChannelListPolicy.filter(ChannelListPolicy.order(all), "  ").size)
    }

    // ---- pill taps ----

    @Test
    fun `tapping 1 on another channel moves channel 1`() {
        assertEquals(Action.SetPrimary("Command"), ChannelListPolicy.onPillTap(0, command, ch1 = "Alpha", ch2 = null))
    }

    @Test
    fun `channel 1 can never be tapped off`() {
        assertTrue(ChannelListPolicy.onPillTap(0, alpha, ch1 = "Alpha", ch2 = "Command") is Action.Explain)
    }

    @Test
    fun `tapping 1 on channel 2 swaps`() {
        assertEquals(Action.Swap, ChannelListPolicy.onPillTap(0, command, ch1 = "Alpha", ch2 = "Command"))
    }

    @Test
    fun `tapping 2 on channel 1 swaps when channel 2 is on`() {
        assertEquals(Action.Swap, ChannelListPolicy.onPillTap(1, alpha, ch1 = "Alpha", ch2 = "Command"))
    }

    @Test
    fun `tapping 2 on channel 1 with channel 2 off explains instead of blanking 1`() {
        assertTrue(ChannelListPolicy.onPillTap(1, alpha, ch1 = "Alpha", ch2 = null) is Action.Explain)
    }

    @Test
    fun `tapping a lit 2 clears channel 2`() {
        assertEquals(Action.ClearSecondary, ChannelListPolicy.onPillTap(1, command, ch1 = "Alpha", ch2 = "Command"))
    }

    @Test
    fun `tapping 2 on another channel sets channel 2`() {
        assertEquals(Action.SetSecondary("Logistics"), ChannelListPolicy.onPillTap(1, logistics, ch1 = "Alpha", ch2 = ""))
    }

    @Test
    fun `no-access channel is never assigned`() {
        assertTrue(ChannelListPolicy.onPillTap(0, restricted, "Alpha", null) is Action.Explain)
        assertTrue(ChannelListPolicy.onPillTap(1, restricted, "Alpha", null) is Action.Explain)
    }

    @Test
    fun `slot names match case-insensitively`() {
        assertEquals(Action.Swap, ChannelListPolicy.onPillTap(0, command, ch1 = "ALPHA", ch2 = " command "))
    }

    // ---- pill state ----

    @Test
    fun `pill state reflects assignment and access`() {
        assertEquals(Pill.ON, ChannelListPolicy.pillState(0, alpha, "Alpha", "Command"))
        assertEquals(Pill.OFF, ChannelListPolicy.pillState(1, alpha, "Alpha", "Command"))
        assertEquals(Pill.ON, ChannelListPolicy.pillState(1, command, "Alpha", "Command"))
        assertEquals(Pill.OFF, ChannelListPolicy.pillState(1, command, "Alpha", null))
        assertEquals(Pill.DISABLED, ChannelListPolicy.pillState(0, restricted, "Alpha", "Command"))
    }
}
