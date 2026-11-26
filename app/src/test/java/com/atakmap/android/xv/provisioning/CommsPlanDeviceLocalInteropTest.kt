package com.atakmap.android.xv.provisioning

import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OpenMANET / VX interop is device-local (one manually enabled gateway at a
 * time): patch fields and interop primaries never leave the device in a
 * comms plan, and are ignored when an older build's plan still carries them.
 */
@RunWith(RobolectricTestRunner::class)
class CommsPlanDeviceLocalInteropTest {
    private val gateway =
        ChannelMulticastConfig(
            channelName = "ops-1",
            cryptoPolicy = CryptoPolicy.REQUIRED,
            patchGroup = "239.1.2.3",
            patchPort = 5007,
            patchWireFormat = WireFormat.OPENMANET_RTP,
            patchCryptoPolicy = CryptoPolicy.CLEARTEXT,
        )

    private fun plan(vararg cfgs: ChannelMulticastConfig) =
        CommsPlan(
            planId = "p",
            name = "n",
            createdAtMs = 1,
            serverIdentity = "tak.example.com",
            channels = cfgs.map { CommsPlan.Channel(it.channelName, it) },
        )

    @Test
    fun `export strips every patch field`() {
        val json = plan(gateway).toCanonicalJson()
        for (field in listOf("patchGroup", "patchPort", "patchWireFormat", "patchCryptoPolicy")) {
            assertFalse("$field must not leave the device", json.contains(field))
        }
        assertFalse(json.contains("239.1.2.3"))
    }

    @Test
    fun `export shares an interop primary as encrypted XV-native without its endpoint`() {
        val interopPrimary =
            ChannelMulticastConfig(
                channelName = "vx",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.4.4.4",
                pinnedPort = 5010,
                patchGroup = "239.5.5.5",
                patchPort = 5011,
            )
        val json = plan(interopPrimary).toCanonicalJson()
        assertFalse(json.contains("239.4.4.4"))
        assertFalse(json.contains("239.5.5.5"))
        val shared = CommsPlan.fromJson(json).channels.single().config
        assertEquals(WireFormat.XV_NATIVE, shared.wireFormat)
        assertEquals(CryptoPolicy.REQUIRED, shared.cryptoPolicy)
        assertNull(shared.pinnedGroup)
        assertNull(shared.validate())
    }

    @Test
    fun `import ignores patch fields an older build still exported`() {
        // Hand-built the way an older build serialized it: config.toJson()
        // with the patch leg included.
        val old =
            JSONObject()
                .put("v", CommsPlan.SCHEMA_VERSION)
                .put("planId", "p")
                .put("name", "n")
                .put("createdAtMs", 1)
                .put(
                    "channels",
                    org.json.JSONArray().put(
                        JSONObject().put("displayName", "Ops 1").put("config", JSONObject(gateway.toJson())),
                    ),
                ).toString()
        val imported = CommsPlan.fromJson(old).channels.single().config
        assertNull(imported.patchGroup)
        assertNull(imported.patchPort)
        assertEquals(gateway.sharedForm(), imported)
    }

    @Test
    fun `clear carrier round trip carries no patch`() {
        val decoded = CommsPlanCarrier.decode(CommsPlanCarrier.encodeClear(plan(gateway)), null)
        assertNull(decoded.channels.single().config.patchGroup)
    }

    @Test
    fun `import keeps this device's own patch leg`() {
        val imported = gateway.sharedForm().copy(mode = com.atakmap.android.xv.transport.multicast.MulticastMode.ALWAYS)
        val merged = imported.withLocalPatchOf(gateway)
        assertEquals("239.1.2.3", merged.patchGroup)
        assertEquals(WireFormat.OPENMANET_RTP, merged.patchWireFormat)
        assertEquals(com.atakmap.android.xv.transport.multicast.MulticastMode.ALWAYS, merged.mode)
        assertEquals("no local config: nothing to keep", imported, imported.withLocalPatchOf(null))
    }
}
