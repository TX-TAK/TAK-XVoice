package com.atakmap.android.xv.ui

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.MulticastMode
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshChannelRowsTest {
    private fun rows(
        candidates: List<String>,
        server: Set<String> = setOf("alpha", "lobby"),
        active: String? = null,
        configs: Map<String, ChannelMulticastConfig> = emptyMap(),
    ) = MeshChannelRows.build(candidates, server, active) { configs[it] }

    @Test
    fun `lobby first, then server channels, then offline, alphabetical`() {
        val r = rows(listOf("Zulu", "alpha", "Lobby", "Bravo"))
        assertEquals(listOf("Lobby", "alpha", "Bravo", "Zulu"), r.map { it.name })
        assertTrue(r[1].serverChannel)
        assertFalse(r[2].serverChannel)
    }

    @Test
    fun `no stored config defaults to Backup, encrypted, no clear badge`() {
        val r = rows(listOf("alpha")).single()
        assertEquals(MulticastMode.FAILOVER, r.mode)
        assertNull(r.interopLabel)
        assertFalse(r.hasClearLeg)
        assertTrue(r.detail.startsWith("Server channel"))
    }

    @Test
    fun `an OpenMANET patch shows OPENMANET and CLEAR`() {
        val cfg =
            ChannelMulticastConfig.defaultFor("alpha").copy(
                patchGroup = "239.1.2.3",
                patchPort = 5007,
                patchWireFormat = WireFormat.OPENMANET_RTP,
            )
        val r = rows(listOf("alpha"), configs = mapOf("alpha" to cfg)).single()
        assertEquals("OPENMANET", r.interopLabel)
        assertTrue(r.hasClearLeg)
        assertTrue(r.detail.contains("Openmanet patch (this device)"))
    }

    @Test
    fun `stored mode and active flag are reflected`() {
        val cfg = ChannelMulticastConfig.defaultFor("Bravo").copy(mode = MulticastMode.ALWAYS)
        val r = rows(listOf("Bravo"), active = "bravo", configs = mapOf("Bravo" to cfg)).single()
        assertEquals(MulticastMode.ALWAYS, r.mode)
        assertTrue(r.active)
        assertTrue(r.detail.startsWith("Offline only"))
        assertTrue(r.detail.endsWith("active"))
    }
}
