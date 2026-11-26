package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.AutoJoinPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoJoinPolicyTest {
    private val directory = mapOf("Alpha" to 5, "Bravo" to 9)
    private val lookup: (String) -> Int? = { directory[it] }

    @Test
    fun `nothing configured stays put`() {
        assertEquals(Action.Stay, AutoJoinPolicy.decide("", isSecondary = false, lookupId = lookup))
        assertEquals(Action.Stay, AutoJoinPolicy.decide("  ", isSecondary = true, lookupId = lookup))
    }

    @Test
    fun `saved channel joins by id`() {
        assertEquals(Action.Join(9), AutoJoinPolicy.decide("Bravo", isSecondary = true, lookupId = lookup))
    }

    @Test
    fun `lobby and root both join id 0 even if the server placed us elsewhere`() {
        assertEquals(Action.Join(0), AutoJoinPolicy.decide("Lobby", isSecondary = false, lookupId = lookup))
        assertEquals(Action.Join(0), AutoJoinPolicy.decide("Root", isSecondary = true, lookupId = lookup))
    }

    @Test
    fun `deleted saved channel 2 drops channel 2`() {
        assertEquals(
            Action.SecondaryMissing("Charlie"),
            AutoJoinPolicy.decide("Charlie", isSecondary = true, lookupId = lookup),
        )
    }

    @Test
    fun `deleted saved channel 1 keeps channel 1 in lobby`() {
        assertEquals(
            Action.PrimaryMissing("Charlie"),
            AutoJoinPolicy.decide("Charlie", isSecondary = false, lookupId = lookup),
        )
    }

    @Test
    fun `root landing before an auto-join elsewhere is transient`() {
        assertTrue(AutoJoinPolicy.landingIsTransient("Bravo", "Lobby"))
        // Server default placement, too.
        assertTrue(AutoJoinPolicy.landingIsTransient("Bravo", "Alpha"))
    }

    @Test
    fun `landing on the saved channel or with nothing saved is settled`() {
        assertFalse(AutoJoinPolicy.landingIsTransient("Lobby", "Lobby"))
        assertFalse(AutoJoinPolicy.landingIsTransient("Root", "Lobby"))
        assertFalse(AutoJoinPolicy.landingIsTransient("bravo", "Bravo"))
        assertFalse(AutoJoinPolicy.landingIsTransient("", "Lobby"))
    }

    @Test
    fun `root is always displayed as lobby`() {
        assertEquals("Lobby", AutoJoinPolicy.displayName(0, "Root"))
        assertEquals("Lobby", AutoJoinPolicy.displayName(0, null))
        assertEquals("Alpha", AutoJoinPolicy.displayName(5, "Alpha"))
    }
}
