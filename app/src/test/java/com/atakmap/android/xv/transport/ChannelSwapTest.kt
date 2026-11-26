package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.ChannelSwap.Decision
import com.atakmap.android.xv.transport.ChannelSwap.Plan
import com.atakmap.android.xv.transport.ChannelSwap.Refusal
import com.atakmap.android.xv.transport.ChannelSwap.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSwapTest {
    private val ready =
        ChannelSwap.State(
            primaryConnected = true,
            primaryChannelId = 5,
            secondaryPresent = true,
            secondaryConnected = true,
            secondaryChannelId = 9,
            inPrivateCall = false,
            transmitting = false,
            swapInFlight = false,
        )

    private fun refusal(s: ChannelSwap.State): Refusal? = (ChannelSwap.decide(s) as? Decision.Refuse)?.reason

    @Test
    fun `ready state proceeds with mirrored targets`() {
        val d = ChannelSwap.decide(ready)
        assertTrue(d is Decision.Proceed)
        val plan = (d as Decision.Proceed).plan
        assertEquals(Plan(primaryFromId = 5, secondaryFromId = 9), plan)
        assertEquals(9, plan.primaryToId)
        assertEquals(5, plan.secondaryToId)
    }

    @Test
    fun `lobby is a valid channel to swap`() {
        val d = ChannelSwap.decide(ready.copy(primaryChannelId = 0))
        assertEquals(0, (d as Decision.Proceed).plan.secondaryToId)
    }

    @Test
    fun `refused while transmitting`() {
        assertEquals(Refusal.TRANSMITTING, refusal(ready.copy(transmitting = true)))
    }

    @Test
    fun `refused during a direct call`() {
        assertEquals(Refusal.IN_CALL, refusal(ready.copy(inPrivateCall = true)))
    }

    @Test
    fun `refused with no channel 2`() {
        assertEquals(
            Refusal.NO_CHANNEL_2,
            refusal(ready.copy(secondaryPresent = false, secondaryConnected = false, secondaryChannelId = -1)),
        )
    }

    @Test
    fun `refused while channel 2 still connecting`() {
        assertEquals(Refusal.CHANNEL_2_NOT_JOINED, refusal(ready.copy(secondaryConnected = false)))
        assertEquals(Refusal.CHANNEL_2_NOT_JOINED, refusal(ready.copy(secondaryChannelId = -1)))
    }

    @Test
    fun `refused when primary not in a channel`() {
        assertEquals(Refusal.NOT_CONNECTED, refusal(ready.copy(primaryConnected = false)))
        assertEquals(Refusal.NOT_CONNECTED, refusal(ready.copy(primaryChannelId = -1)))
    }

    @Test
    fun `refused when both slots already share a channel`() {
        assertEquals(Refusal.SAME_CHANNEL, refusal(ready.copy(secondaryChannelId = 5)))
    }

    @Test
    fun `second swap refused until first resolves`() {
        assertEquals(Refusal.SWAP_IN_PROGRESS, refusal(ready.copy(swapInFlight = true)))
    }

    @Test
    fun `transmitting reason wins over topology problems`() {
        // Operator keyed with no channel 2: tell them about the key-up first.
        assertEquals(Refusal.TRANSMITTING, refusal(ready.copy(transmitting = true, secondaryPresent = false)))
    }

    private val plan = Plan(primaryFromId = 5, secondaryFromId = 9)

    @Test
    fun `both acks complete the swap even before the deadline`() {
        assertEquals(Resolution.Done, ChannelSwap.resolve(plan, observedPrimaryId = 9, observedSecondaryId = 5, timedOut = false))
        assertEquals(Resolution.Done, ChannelSwap.resolve(plan, observedPrimaryId = 9, observedSecondaryId = 5, timedOut = true))
    }

    @Test
    fun `one ack before the deadline keeps waiting`() {
        assertEquals(Resolution.Wait, ChannelSwap.resolve(plan, observedPrimaryId = 9, observedSecondaryId = 9, timedOut = false))
        assertEquals(Resolution.Wait, ChannelSwap.resolve(plan, observedPrimaryId = 5, observedSecondaryId = 5, timedOut = false))
        assertEquals(Resolution.Wait, ChannelSwap.resolve(plan, observedPrimaryId = 5, observedSecondaryId = 9, timedOut = false))
    }

    @Test
    fun `only primary moved at deadline rolls primary back`() {
        // VS2 refused its join; VS1 is now on VS2's channel.
        assertEquals(
            Resolution.RollBackPrimary(toId = 5),
            ChannelSwap.resolve(plan, observedPrimaryId = 9, observedSecondaryId = 9, timedOut = true),
        )
    }

    @Test
    fun `only secondary moved at deadline rolls secondary back`() {
        assertEquals(
            Resolution.RollBackSecondary(toId = 9),
            ChannelSwap.resolve(plan, observedPrimaryId = 5, observedSecondaryId = 5, timedOut = true),
        )
    }

    @Test
    fun `nothing moved at deadline reports failure without joins`() {
        assertEquals(
            Resolution.NothingMoved,
            ChannelSwap.resolve(plan, observedPrimaryId = 5, observedSecondaryId = 9, timedOut = true),
        )
    }

    @Test
    fun `secondary torn down mid-swap rolls primary back`() {
        // Secondary slot gone (-1) but primary landed: undo the primary.
        assertEquals(
            Resolution.RollBackPrimary(toId = 5),
            ChannelSwap.resolve(plan, observedPrimaryId = 9, observedSecondaryId = -1, timedOut = true),
        )
    }

    @Test
    fun `secondary rx dropped only while sharing primary's channel`() {
        assertTrue(ChannelSwap.shouldDropSecondaryRx(slotIdx = 1, slotChannelId = 9, primaryChannelId = 9))
        assertTrue(ChannelSwap.shouldDropSecondaryRx(slotIdx = 1, slotChannelId = 0, primaryChannelId = 0))
        assertFalse(ChannelSwap.shouldDropSecondaryRx(slotIdx = 1, slotChannelId = 9, primaryChannelId = 5))
        assertFalse(ChannelSwap.shouldDropSecondaryRx(slotIdx = 0, slotChannelId = 9, primaryChannelId = 9))
        assertFalse(ChannelSwap.shouldDropSecondaryRx(slotIdx = 1, slotChannelId = -1, primaryChannelId = -1))
    }

    @Test
    fun `every refusal has an operator message`() {
        for (r in Refusal.entries) {
            assertTrue(ChannelSwap.refusalMessage(r).isNotBlank())
        }
    }
}
