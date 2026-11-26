package com.atakmap.android.xv.ui

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
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

    @Test
    fun `VX interop primary shows VX and CLEAR`() {
        val cfg =
            ChannelMulticastConfig.defaultFor("Bravo").copy(
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.1.2.3",
                pinnedPort = 5008,
            )
        val r = rows(listOf("Bravo"), configs = mapOf("Bravo" to cfg)).single()
        assertEquals("VX", r.interopLabel)
        assertTrue(r.hasClearLeg)
    }

    @Test
    fun `encrypted XV-native patch shows no badge`() {
        val cfg =
            ChannelMulticastConfig.defaultFor("alpha").copy(
                patchGroup = "239.1.2.4",
                patchPort = 5009,
                patchWireFormat = WireFormat.XV_NATIVE,
                patchCryptoPolicy = CryptoPolicy.REQUIRED,
            )
        val r = rows(listOf("alpha"), configs = mapOf("alpha" to cfg)).single()
        assertNull(r.interopLabel)
        assertFalse(r.hasClearLeg)
    }

    @Test
    fun `cleartext XV-native patch shows CLEAR`() {
        val cfg =
            ChannelMulticastConfig.defaultFor("alpha").copy(
                patchGroup = "239.1.2.4",
                patchPort = 5009,
                patchWireFormat = WireFormat.XV_NATIVE,
                patchCryptoPolicy = CryptoPolicy.CLEARTEXT,
            )
        val r = rows(listOf("alpha"), configs = mapOf("alpha" to cfg)).single()
        assertEquals("XV", r.interopLabel)
        assertTrue(r.hasClearLeg)
    }

    @Test
    fun `Root sorts first as Lobby`() {
        val r = rows(listOf("Charlie", "Root", "alpha"), server = setOf("alpha"))
        assertEquals("Root", r.first().name)
    }

    @Test
    fun `active and server matching ignore case`() {
        val r = rows(listOf("ALPHA"), server = setOf("alpha"), active = "alpha").single()
        assertTrue(r.serverChannel)
        assertTrue(r.active)
    }

    @Test
    fun `empty candidate list gives no rows`() {
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `detail lists patch then active`() {
        val cfg =
            ChannelMulticastConfig.defaultFor("alpha").copy(
                patchGroup = "239.1.2.3",
                patchPort = 5007,
                patchWireFormat = WireFormat.OPENMANET_RTP,
            )
        val r = rows(listOf("alpha"), active = "alpha", configs = mapOf("alpha" to cfg)).single()
        assertEquals("Server channel · mesh leg auto-derived · Openmanet patch (this device) · active", r.detail)
    }
}
