package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.MulticastGroupDerivation
import com.atakmap.android.xv.transport.multicast.MulticastMode
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
        val r = MeshChannelSpec.build("Ops-1", group = null, port = null)
        assertNull(r.error)
        assertNotNull(r.config)
        assertNull("no manual pin", r.config!!.pinnedGroup)
        assertNull(r.config.pinnedPort)
        assertEquals(MulticastGroupDerivation.canonicalChannelName("Ops-1"), r.config.channelName)
        assertTrue("encrypted channel generates a key", r.autoKey)
    }

    @Test
    fun `a new channel is always XV-native and encrypted only`() {
        val r = MeshChannelSpec.build("Ops-1", group = "239.1.2.3", port = "5007")
        assertEquals(WireFormat.XV_NATIVE, r.config!!.wireFormat)
        assertEquals(CryptoPolicy.REQUIRED, r.config.cryptoPolicy)
        assertNull("no interop patch is created here", r.config.patchGroup)
    }

    @Test
    fun `blank name is rejected`() {
        val r = MeshChannelSpec.build("  ", group = null, port = null)
        assertNotNull(r.error)
        assertNull(r.config)
    }

    @Test
    fun `blank group and port strings are treated as derive`() {
        val r = MeshChannelSpec.build("Ops-1", group = "  ", port = "")
        assertNull(r.error)
        assertNull(r.config!!.pinnedGroup)
    }

    // ---- pin validation ----

    @Test
    fun `a fixed group and port are pinned`() {
        val r = MeshChannelSpec.build("mesh-ptt", group = "239.1.2.3", port = "5007")
        assertNull(r.error)
        assertEquals("239.1.2.3", r.config!!.pinnedGroup)
        assertEquals(5007, r.config.pinnedPort)
    }

    @Test
    fun `group without port is rejected`() {
        assertNotNull(MeshChannelSpec.build("x", group = "239.1.2.3", port = null).error)
    }

    @Test
    fun `port without group is rejected`() {
        assertNotNull(MeshChannelSpec.build("x", group = null, port = "5007").error)
    }

    @Test
    fun `non-numeric port is rejected with a clear message`() {
        val r = MeshChannelSpec.build("x", group = "239.1.2.3", port = "abc")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("abc"))
    }

    @Test
    fun `out-of-range port is rejected via config validation`() {
        assertNotNull(MeshChannelSpec.build("x", group = "239.1.2.3", port = "70000").error)
    }

    @Test
    fun `non-multicast pinned group is rejected via config validation`() {
        assertNotNull(MeshChannelSpec.build("x", group = "192.0.2.10", port = "5007").error)
    }

    // ---- editing an existing channel (must not silently re-key / reset) ----

    private val existingEncrypted =
        ChannelMulticastConfig(
            channelName = "ops",
            mode = MulticastMode.ALWAYS,
            wireFormat = WireFormat.XV_NATIVE,
            cryptoPolicy = CryptoPolicy.REQUIRED,
            patchGroup = "239.1.2.3",
            patchPort = 5007,
            patchWireFormat = WireFormat.OPENMANET_RTP,
            patchCryptoPolicy = CryptoPolicy.CLEARTEXT,
        )

    @Test
    fun `edit keeps mode and patch leg and never mints a key`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = "", port = "")
        assertNull(r.error)
        assertFalse("editing never mints a key over the held one", r.autoKey)
        assertEquals(existingEncrypted, r.config)
    }

    @Test
    fun `edit only pins what the operator typed, never the patch address`() {
        val r = MeshChannelSpec.edit(existingEncrypted, group = "", port = "")
        assertNull(r.config!!.pinnedGroup)
        assertNull(r.config.pinnedPort)
        assertEquals("239.1.2.3", r.config.patchGroup)
    }

    @Test
    fun `edit can change the pin and keeps everything else`() {
        val r = MeshChannelSpec.edit(existingEncrypted, "239.9.9.9", "6000")
        assertNull(r.error)
        assertEquals(existingEncrypted.copy(pinnedGroup = "239.9.9.9", pinnedPort = 6000), r.config)
        assertFalse(r.autoKey)
    }

    @Test
    fun `edit of a stale PREFERRED or CLEARTEXT XV-native channel yields REQUIRED`() {
        for (old in listOf(CryptoPolicy.PREFERRED, CryptoPolicy.CLEARTEXT)) {
            val r = MeshChannelSpec.edit(existingEncrypted.copy(cryptoPolicy = old), null, null)
            assertEquals(CryptoPolicy.REQUIRED, r.config!!.cryptoPolicy)
            assertFalse(r.autoKey)
        }
    }

    @Test
    fun `half a pin is rejected on edit too`() {
        val r = MeshChannelSpec.edit(existingEncrypted, "239.1.1.1", "")
        assertNotNull(r.error)
        assertNull(r.config)
    }
}
