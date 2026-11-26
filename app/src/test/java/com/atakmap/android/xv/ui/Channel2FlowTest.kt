package com.atakmap.android.xv.ui

import com.atakmap.android.xv.plugin.PttSlotGate
import com.atakmap.android.xv.transport.SlotRouting
import com.atakmap.android.xv.ui.ChannelListPolicy.Access
import com.atakmap.android.xv.ui.ChannelListPolicy.Action
import com.atakmap.android.xv.ui.ChannelListPolicy.Channel
import com.atakmap.android.xv.ui.ChannelListPolicy.Pill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel 2 add / remove from the channel list, end to end over the
 * pure rules: the pill tap's action, the list state it leaves, and where
 * PTT2 goes afterwards. Each tap reads the CURRENT slots (as the list
 * now does at tap time), so a quick second tap can't act on stale state.
 */
class Channel2FlowTest {
    private val alpha = Channel(1, "Alpha", 0, Access.FULL)
    private val bravo = Channel(2, "Bravo", 0, Access.FULL)
    private var ch1: String? = "Alpha"
    private var ch2: String? = null

    private fun tap(
        slot: Int,
        c: Channel,
    ) {
        when (val a = ChannelListPolicy.onPillTap(slot, c, ch1, ch2)) {
            is Action.SetSecondary -> ch2 = a.name
            Action.ClearSecondary -> ch2 = null
            is Action.SetPrimary -> ch1 = a.name
            else -> Unit
        }
    }

    private fun pill(
        slot: Int,
        c: Channel,
    ) = ChannelListPolicy.pillState(slot, c, ch1, ch2)

    @Test
    fun `tapping 2 adds channel 2 and the list marks it`() {
        tap(1, bravo)
        assertEquals("Bravo", ch2)
        assertEquals(Pill.ON, pill(1, bravo))
        assertEquals(Pill.ON, pill(0, alpha))
    }

    @Test
    fun `tapping the lit 2 removes channel 2 directly`() {
        tap(1, bravo)
        tap(1, bravo)
        assertEquals(null, ch2)
        assertEquals(Pill.OFF, pill(1, bravo))
        assertEquals("Alpha", ch1) // channel 1 is never blank
    }

    @Test
    fun `with channel 2 removed PTT2 falls back to channel 1`() {
        tap(1, bravo)
        tap(1, bravo)
        val route = SlotRouting.route(
            1,
            secondaryConfigured = ch2 != null,
            secondaryPresent = false,
            secondaryJoined = false,
            sameChannelAsPrimary = false
        )
        assertEquals(SlotRouting.Route.PRIMARY, route)
        assertTrue(PttSlotGate.canSpeakSlot1(channel2On = false, channel2Live = false, slot0Suppressed = false, slot1Suppressed = true))
    }

    @Test
    fun `a second tap after an add acts on the new state, not the old`() {
        tap(1, bravo) // add
        // Old behaviour captured ch2=null at row build and re-added; with
        // live state the second tap is the remove the operator asked for.
        val second = ChannelListPolicy.onPillTap(1, bravo, ch1, ch2)
        assertEquals(Action.ClearSecondary, second)
    }

    @Test
    fun `channel 2 pill on channel 1 is greyed and never swaps or blanks channel 1`() {
        tap(1, bravo)
        assertEquals(Pill.DISABLED, pill(1, alpha))
        val a = ChannelListPolicy.onPillTap(1, alpha, ch1, ch2)
        assertTrue(a is Action.Explain)
        assertFalse(a is Action.ClearSecondary)
        tap(1, alpha)
        assertEquals("Alpha", ch1)
        assertEquals("Bravo", ch2)
    }
}
