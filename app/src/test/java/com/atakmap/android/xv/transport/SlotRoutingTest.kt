package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.SlotRouting.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotRoutingTest {
    private fun route(
        slot: Int,
        configured: Boolean = true,
        present: Boolean = true,
        joined: Boolean = true,
        same: Boolean = false,
    ) = SlotRouting.route(slot, configured, present, joined, same)

    @Test
    fun `slot 0 always goes out on the primary`() {
        assertEquals(Route.PRIMARY, route(0))
        assertEquals(Route.PRIMARY, route(0, configured = false, present = false, joined = false))
    }

    @Test
    fun `channel 2 off - PTT2 keys channel 1`() {
        assertEquals(Route.PRIMARY, route(1, configured = false, present = false, joined = false))
    }

    @Test
    fun `channel 2 configured but not live drops PTT2 frames`() {
        assertEquals(Route.DROP, route(1, present = false, joined = false))
        assertEquals(Route.DROP, route(1, joined = false))
    }

    @Test
    fun `channel 2 live sends on the secondary`() {
        assertEquals(Route.SECONDARY, route(1))
    }

    @Test
    fun `mid-swap overlap carries PTT2 on the primary`() {
        assertEquals(Route.PRIMARY, route(1, same = true))
    }

    @Test
    fun `secondary session still up after channel 2 cleared is not used blindly`() {
        // Cleared but the session hasn't torn down yet: joined -> secondary.
        assertEquals(Route.SECONDARY, route(1, configured = false))
    }
}
