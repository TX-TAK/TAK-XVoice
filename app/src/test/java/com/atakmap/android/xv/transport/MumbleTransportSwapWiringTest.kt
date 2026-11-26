package com.atakmap.android.xv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MumbleTransport's swap wiring, without a server: the guards that must
 * hold before any session exists, and that disconnect / clearing channel
 * 2 never leave a swap in flight or fire a stray outcome. The state
 * machine itself is covered by ChannelSwapCoordinatorTest.
 */
@RunWith(RobolectricTestRunner::class)
class MumbleTransportSwapWiringTest {
    private val outcomes = mutableListOf<ChannelSwap.Outcome>()

    private fun transport(secondary: String? = "Bravo") =
        MumbleTransport(
            config =
            TransportConfig.Mumble(
                host = "tak.example",
                username = "test",
                channelName = "Alpha",
                secondaryChannelName = secondary,
            ),
            onChannelsSwapped = { outcomes += it },
        )

    @Test
    fun `swap is refused before connect and never goes in flight`() {
        val t = transport()
        val d = t.swapChannels(transmitting = false)
        assertEquals(ChannelSwap.Decision.Refuse(ChannelSwap.Refusal.NOT_CONNECTED), d)
        assertFalse(t.isSwapInFlight())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `preview matches the swap decision`() {
        val t = transport()
        assertEquals(t.previewSwap(transmitting = true), ChannelSwap.Decision.Refuse(ChannelSwap.Refusal.TRANSMITTING))
        assertEquals(t.previewSwap(transmitting = false), t.swapChannels(transmitting = false))
    }

    @Test
    fun `disconnect and clearing channel 2 with no swap report nothing`() {
        val t = transport()
        t.retargetSecondary("")
        t.disconnect()
        assertFalse(t.isSwapInFlight())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `channel 1 can't join Lobby while channel 2 is Root, and vice versa`() {
        // Lobby is XV's name for Root: the collision check must see them
        // as one channel and run before the Lobby shortcut.
        assertFalse(transport(secondary = "Root").joinChannel("Lobby"))
        assertFalse(transport(secondary = "Lobby").joinChannel("lobby"))
        assertFalse(transport(secondary = "Bravo").joinChannel("bravo"))
    }
}
