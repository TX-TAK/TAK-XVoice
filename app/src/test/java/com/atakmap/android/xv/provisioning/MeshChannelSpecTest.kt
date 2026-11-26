package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshChannelSpecTest {
    // ---- the "just name it" case (no pinning) ----

    @Test
    fun `named channel with no pin derives its endpoint and auto-keys`() {
        val r = MeshChannelSpec.build("Ops-1", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNull(r.error)
        assertNotNull(r.config)
        assertNull("no manual pin", r.config!!.pinnedGroup)
        assertNull(r.config.pinnedPort)
        assertEquals(MulticastGroupDerivation.canonicalChannelName("Ops-1"), r.config.channelName)
        assertTrue("encrypted posture generates a key", r.autoKey)
    }

    @Test
    fun `blank name is rejected`() {
        val r = MeshChannelSpec.build("  ", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
        assertNull(r.config)
    }

    @Test
    fun `blank group and port strings are treated as derive`() {
        val r = MeshChannelSpec.build("Ops-1", group = "  ", port = "", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNull(r.error)
        assertNull(r.config!!.pinnedGroup)
    }

    // ---- the interop / pin case (path 3) ----

    @Test
    fun `openmanet interop channel pins group and port with cleartext`() {
        val r =
            MeshChannelSpec.build(
                "mesh-ptt",
                group = "224.0.0.1",
                port = "5007",
                WireFormat.VX_COMPAT,
                CryptoPolicy.CLEARTEXT,
            )
        assertNull(r.error)
        assertEquals("224.0.0.1", r.config!!.pinnedGroup)
        assertEquals(5007, r.config.pinnedPort)
        assertFalse("cleartext generates no key", r.autoKey)
    }

    @Test
    fun `group without port is rejected`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = null, WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `port without group is rejected`() {
        val r = MeshChannelSpec.build("x", group = null, port = "5007", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `non-numeric port is rejected with a clear message`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = "abc", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("abc"))
    }

    @Test
    fun `out-of-range port is rejected via config validation`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = "70000", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `non-multicast pinned group is rejected via config validation`() {
        val r = MeshChannelSpec.build("x", group = "192.0.2.10", port = "5007", WireFormat.XV_NATIVE, CryptoPolicy.PREFERRED)
        assertNotNull(r.error)
    }

    @Test
    fun `openmanet compat without a pin is rejected`() {
        val r = MeshChannelSpec.build("x", group = null, port = null, WireFormat.VX_COMPAT, CryptoPolicy.CLEARTEXT)
        assertNotNull(r.error)
    }

    @Test
    fun `openmanet compat with encryption is rejected`() {
        val r =
            MeshChannelSpec.build(
                "x",
                group = "224.0.0.1",
                port = "5007",
                WireFormat.VX_COMPAT,
                CryptoPolicy.REQUIRED,
            )
        assertNotNull(r.error)
    }

    @Test
    fun `required crypto auto-keys`() {
        val r = MeshChannelSpec.build("secure-1", group = null, port = null, WireFormat.XV_NATIVE, CryptoPolicy.REQUIRED)
        assertNull(r.error)
        assertTrue(r.autoKey)
    }

    // ---- editing an existing channel (must not silently re-key / reset) ----

    private val existingEncrypted =
        com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig(
            channelName = "ops",
            mode = com.atakmap.android.xv.transport.multicast.MulticastMode.ALWAYS,
            wireFormat = WireFormat.XV_NATIVE,
            cryptoPolicy = CryptoPolicy.REQUIRED,
            patchGroup = "239.1.2.3",
            patchPort = 5007,
            patchWireFormat = WireFormat.OPENMANET_RTP,
            patchCryptoPolicy = CryptoPolicy.CLEARTEXT,
        )

    @Test
    fun `edit keeps the key, mode and patch leg of a keyed channel`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = "", port = "", CryptoPolicy.REQUIRED, hasKey = true)
        assertNull(r.error)
        assertFalse("editing never mints a fresh key over the held one", r.autoKey)
        assertEquals(existingEncrypted, r.config)
    }

    @Test
    fun `edit of an encrypted channel still awaiting its key leaves keying to the election`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = null, port = null, CryptoPolicy.PREFERRED, hasKey = false)
        assertNull(r.error)
        assertFalse(r.autoKey)
        assertEquals(CryptoPolicy.PREFERRED, r.config!!.cryptoPolicy)
    }

    @Test
    fun `edit round-trips REQUIRED without downgrading it`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = null, port = null, CryptoPolicy.REQUIRED, hasKey = true)
        assertEquals(CryptoPolicy.REQUIRED, r.config!!.cryptoPolicy)
    }

    @Test
    fun `edit only pins what the operator typed, never the patch address`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = "", port = "", CryptoPolicy.REQUIRED, hasKey = true)
        assertNull(r.config!!.pinnedGroup)
        assertNull(r.config.pinnedPort)
        assertEquals("239.1.2.3", r.config.patchGroup)
    }

    @Test
    fun `edit can change the pin and keeps everything else`() {
        val r = MeshChannelSpec.edit(existingEncrypted, "239.9.9.9", "6000", CryptoPolicy.REQUIRED, hasKey = true)
        assertNull(r.error)
        assertEquals(existingEncrypted.copy(pinnedGroup = "239.9.9.9", pinnedPort = 6000), r.config)
        assertFalse(r.autoKey)
    }

    @Test
    fun `cleartext channel turned encrypted with no key gets one, on XV-native framing`() {
        val clear =
            existingEncrypted.copy(
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.4.4.4",
                pinnedPort = 5010,
            )
        val r = MeshChannelSpec.edit(clear, "239.4.4.4", "5010", CryptoPolicy.REQUIRED, hasKey = false)
        assertNull(r.error)
        assertTrue(r.autoKey)
        assertEquals(WireFormat.XV_NATIVE, r.config!!.wireFormat)
    }

    @Test
    fun `cleartext channel turned encrypted keeps a key it already holds`() {
        val clear = existingEncrypted.copy(cryptoPolicy = CryptoPolicy.CLEARTEXT)
        val r = MeshChannelSpec.edit(clear, null, null, CryptoPolicy.PREFERRED, hasKey = true)
        assertFalse(r.autoKey)
    }

    @Test
    fun `cleartext interop channel keeps its wire format when left cleartext`() {
        val openManet =
            existingEncrypted.copy(
                wireFormat = WireFormat.OPENMANET_RTP,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.5.5.5",
                pinnedPort = 5020,
            )
        val r = MeshChannelSpec.edit(openManet, "239.5.5.5", "5020", CryptoPolicy.CLEARTEXT, hasKey = false)
        assertNull(r.error)
        assertEquals(openManet, r.config)
        assertFalse(r.autoKey)
    }

    @Test
    fun `half a pin is rejected on edit too`() {
        val r = MeshChannelSpec.edit(existingEncrypted, "239.1.1.1", "", CryptoPolicy.REQUIRED, hasKey = true)
        assertNotNull(r.error)
        assertNull(r.config)
    }
}
