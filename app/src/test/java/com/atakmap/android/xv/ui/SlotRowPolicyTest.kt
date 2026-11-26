package com.atakmap.android.xv.ui

import com.atakmap.android.xv.ui.SlotRowPolicy.Idle
import com.atakmap.android.xv.ui.SlotRowPolicy.MeshTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotRowPolicyTest {
    @Test
    fun `remembered channel with no link is not live`() {
        assertFalse(SlotRowPolicy.channel1Live("Alpha", serverConnected = false, meshCarrying = false))
        assertFalse(SlotRowPolicy.channel1Live(null, serverConnected = true, meshCarrying = true))
    }

    @Test
    fun `server or carrying mesh makes channel 1 live`() {
        assertTrue(SlotRowPolicy.channel1Live("Alpha", serverConnected = true, meshCarrying = false))
        assertTrue(SlotRowPolicy.channel1Live("Alpha", serverConnected = false, meshCarrying = true))
    }

    @Test
    fun `server down with mesh carrying reads mesh on row 1 only`() {
        assertEquals(Idle.MESH, SlotRowPolicy.idle(0, live = true, serverConnected = false, listenOnly = false))
        assertEquals(Idle.NOT_CONNECTED, SlotRowPolicy.idle(1, live = true, serverConnected = false, listenOnly = false))
        assertEquals(Idle.NOT_CONNECTED, SlotRowPolicy.idle(0, live = false, serverConnected = false, listenOnly = false))
    }

    @Test
    fun `connected rows read joining, listen only or quiet`() {
        assertEquals(Idle.JOINING, SlotRowPolicy.idle(1, live = false, serverConnected = true, listenOnly = false))
        assertEquals(Idle.LISTEN_ONLY, SlotRowPolicy.idle(0, live = true, serverConnected = true, listenOnly = true))
        assertEquals(Idle.QUIET, SlotRowPolicy.idle(0, live = true, serverConnected = true, listenOnly = false))
    }

    @Test
    fun `mesh tone is red only for clear, caution for key needed`() {
        assertEquals(MeshTone.CLEAR, SlotRowPolicy.meshTone(cleartext = true, keyNeeded = true))
        assertEquals(MeshTone.KEY_NEEDED, SlotRowPolicy.meshTone(cleartext = false, keyNeeded = true))
        assertEquals(MeshTone.OK, SlotRowPolicy.meshTone(cleartext = false, keyNeeded = false))
    }

    @Test
    fun `mesh card shows encrypted or clear, never both`() {
        assertTrue(SlotRowPolicy.showEncryptedChip(hasClearLeg = false))
        assertFalse(SlotRowPolicy.showEncryptedChip(hasClearLeg = true))
    }
}
