package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real org.json — the mockable android.jar returns
// default values (nulls) from JSONObject, silently corrupting the
// canonical-JSON assertions this class exists to pin.
@RunWith(RobolectricTestRunner::class)
class ChannelMulticastConfigTest {
    private val server = ServerIdentity.fromHostname("tak.example.com")

    // ---- defaults ----

    @Test
    fun `defaultFor is a valid auto-derived FAILOVER leg`() {
        val cfg = ChannelMulticastConfig.defaultFor("Ops-1")
        assertEquals(MulticastMode.FAILOVER, cfg.mode)
        assertEquals(WireFormat.XV_NATIVE, cfg.wireFormat)
        // Encrypted only (operator decision 2026-11-25): an unkeyed
        // failover leg is silent, never cleartext on the air.
        assertEquals(CryptoPolicy.REQUIRED, cfg.cryptoPolicy)
        assertNull(cfg.pinnedGroup)
        assertNull(cfg.pinnedPort)
        assertNull(cfg.validate())
    }

    @Test
    fun `defaultFor canonicalizes the channel name so lookups cannot fork on spelling`() {
        assertEquals("ops-1", ChannelMulticastConfig.defaultFor("  OPS-1 ").channelName)
        assertEquals(
            ChannelMulticastConfig.defaultFor("ops-1"),
            ChannelMulticastConfig.defaultFor("Ops-1"),
        )
    }

    // ---- validate ----

    @Test
    fun `blank channel name is invalid`() {
        assertNotNull(ChannelMulticastConfig(channelName = "  ").validate())
    }

    @Test
    fun `pinned group and port must be set together`() {
        assertNotNull(ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3").validate())
        assertNotNull(ChannelMulticastConfig(channelName = "ops-1", pinnedPort = 5007).validate())
        assertNull(
            ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3", pinnedPort = 5007).validate(),
        )
    }

    @Test
    fun `pinned port must be a real UDP port`() {
        fun withPort(p: Int) = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "239.1.2.3", pinnedPort = p)
        assertNotNull(withPort(0).validate())
        assertNotNull(withPort(1023).validate())
        assertNotNull(withPort(65536).validate())
        assertNull(withPort(1024).validate())
        assertNull(withPort(65535).validate())
    }

    @Test
    fun `pinned group must be an ipv4 multicast address`() {
        fun withGroup(g: String) = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = g, pinnedPort = 5007)
        // Class D boundaries.
        assertNotNull(withGroup("224.0.0.0").validate())
        assertNull(withGroup("224.0.0.1").validate())
        assertNull(withGroup("239.255.255.255").validate())
        assertNotNull(withGroup("223.255.255.255").validate())
        assertNotNull(withGroup("240.0.0.0").validate())
        // Unicast / structurally broken inputs.
        assertNotNull(withGroup("192.0.2.10").validate())
        assertNotNull(withGroup("239.1.2").validate())
        assertNotNull(withGroup("239.1.2.3.4").validate())
        assertNotNull(withGroup("239.1.2.256").validate())
        assertNotNull(withGroup("not-an-address").validate())
    }

    @Test
    fun `openmanet compat demands an explicit pin`() {
        val cfg =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
            )
        assertNotNull(cfg.validate())
    }

    @Test
    fun `openmanet compat demands cleartext`() {
        fun withCrypto(c: CryptoPolicy) =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = c,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            )
        assertNotNull(withCrypto(CryptoPolicy.REQUIRED).validate())
        assertNotNull(withCrypto(CryptoPolicy.PREFERRED).validate())
        assertNull(withCrypto(CryptoPolicy.CLEARTEXT).validate())
    }

    // ---- endpoint resolution ----

    @Test
    fun `resolveEndpoint prefers the operator pin`() {
        val cfg = ChannelMulticastConfig(channelName = "ops-1", pinnedGroup = "224.0.0.1", pinnedPort = 5007)
        assertEquals(MulticastEndpoint("224.0.0.1", 5007), cfg.resolveEndpoint(server))
    }

    @Test
    fun `resolveEndpoint falls back to the v1 derivation when unpinned`() {
        val cfg = ChannelMulticastConfig.defaultFor("Ops-1")
        assertEquals(MulticastGroupDerivation.derive(server, "Ops-1"), cfg.resolveEndpoint(server))
    }

    // ---- canonical JSON ----

    @Test
    fun `toJson is byte-stable with the frozen v1 field order`() {
        // Known-answer pin: comms-plan signatures depend on this exact
        // string. If this test needs editing, that is a schema change.
        assertEquals(
            """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}""",
            ChannelMulticastConfig.defaultFor("ops-1").copy(cryptoPolicy = CryptoPolicy.PREFERRED).toJson(),
        )
        assertEquals(
            """{"channel":"mesh-ptt","mode":"ALWAYS","wireFormat":"VX_COMPAT","cryptoPolicy":"CLEARTEXT","group":"224.0.0.1","port":5007}""",
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                mode = MulticastMode.ALWAYS,
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            ).toJson(),
        )
    }

    @Test
    fun `json round-trips without and with pins`() {
        val derived = ChannelMulticastConfig.defaultFor("ops-1")
        assertEquals(derived, ChannelMulticastConfig.fromJson(derived.toJson()))

        val pinned =
            ChannelMulticastConfig(
                channelName = "mesh-ptt",
                mode = MulticastMode.ALWAYS,
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "224.0.0.1",
                pinnedPort = 5007,
            )
        assertEquals(pinned, ChannelMulticastConfig.fromJson(pinned.toJson()))
    }

    @Test
    fun `json escaping survives hostile channel names`() {
        val cfg = ChannelMulticastConfig.defaultFor("""ops "quoted" \ slash""")
        val parsed = ChannelMulticastConfig.fromJson(cfg.toJson())
        assertEquals(cfg, parsed)
    }

    @Test
    fun `fromJson degrades to null instead of crashing`() {
        // A NEWER build's enum value must read as "no override".
        assertNull(
            ChannelMulticastConfig.fromJson(
                """{"channel":"ops-1","mode":"TELEPATHY","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED"}""",
            ),
        )
        // Missing required field.
        assertNull(ChannelMulticastConfig.fromJson("""{"channel":"ops-1"}"""))
        // Not JSON at all.
        assertNull(ChannelMulticastConfig.fromJson("not json"))
    }

    @Test
    fun `fromJson accepts a validatable but unvalidated config`() {
        // fromJson is parse-only; validation is the caller's second
        // step. A structurally sound but semantically broken override
        // (compat without a pin) must parse so the UI can show WHY it
        // is rejected instead of silently reverting to defaults.
        val parsed =
            ChannelMulticastConfig.fromJson(
                """{"channel":"mesh-ptt","mode":"ALWAYS","wireFormat":"VX_COMPAT","cryptoPolicy":"CLEARTEXT"}""",
            )
        assertNotNull(parsed)
        assertTrue(parsed!!.validate() != null)
    }

    // ---- OPENMANET_RTP ----

    private fun openManet(
        crypto: CryptoPolicy = CryptoPolicy.CLEARTEXT,
        pinned: Boolean = true,
    ) = ChannelMulticastConfig(
        channelName = "mesh-ptt",
        wireFormat = WireFormat.OPENMANET_RTP,
        cryptoPolicy = crypto,
        pinnedGroup = if (pinned) "239.255.0.1" else null,
        pinnedPort = if (pinned) 5007 else null,
    )

    @Test
    fun `openmanet rtp demands a pin and cleartext like other interop formats`() {
        assertNull(openManet().validate())
        assertNotNull(openManet(pinned = false).validate())
        assertNotNull(openManet(crypto = CryptoPolicy.REQUIRED).validate())
        assertNotNull(openManet(crypto = CryptoPolicy.PREFERRED).validate())
    }

    @Test
    fun `openmanet rtp patch leg round-trips through json`() {
        val cfg =
            ChannelMulticastConfig(
                channelName = "ops-1",
                patchGroup = "239.255.0.2",
                patchPort = 5008,
                patchWireFormat = WireFormat.OPENMANET_RTP,
                patchCryptoPolicy = CryptoPolicy.CLEARTEXT,
            )
        assertEquals(cfg, ChannelMulticastConfig.fromJson(cfg.toJson()))
        assertNull(cfg.validate())
        assertNotNull(cfg.copy(patchCryptoPolicy = CryptoPolicy.PREFERRED).validate())
    }

    @Test
    fun `stored patch config without patchWireFormat still reads as vx compat`() {
        val json =
            """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"XV_NATIVE","cryptoPolicy":"PREFERRED",""" +
                """"patchGroup":"239.255.0.2","patchPort":5008}"""
        assertEquals(WireFormat.VX_COMPAT, ChannelMulticastConfig.fromJson(json)!!.patchWireFormat)
    }

    @Test
    fun `a wire format this build does not know degrades to no override`() {
        val json = """{"channel":"ops-1","mode":"FAILOVER","wireFormat":"FUTURE_FORMAT","cryptoPolicy":"CLEARTEXT"}"""
        assertNull(ChannelMulticastConfig.fromJson(json))
    }

    // ---- gear-dialog policy change ----

    @Test
    fun `choosing an encrypted policy re-encrypts an interop-framed primary`() {
        for (format in listOf(WireFormat.VX_COMPAT, WireFormat.OPENMANET_RTP)) {
            val interop =
                ChannelMulticastConfig(
                    channelName = "ops-1",
                    wireFormat = format,
                    cryptoPolicy = CryptoPolicy.CLEARTEXT,
                    pinnedGroup = "239.1.2.3",
                    pinnedPort = 5007,
                    patchGroup = "239.9.9.9",
                    patchPort = 5010,
                )
            for (policy in listOf(CryptoPolicy.REQUIRED, CryptoPolicy.PREFERRED)) {
                val updated = interop.withPrimaryCryptoPolicy(policy)
                assertNull("$format -> $policy must validate", updated.validate())
                assertEquals(WireFormat.XV_NATIVE, updated.wireFormat)
                assertEquals(policy, updated.cryptoPolicy)
                assertEquals("pin and patch are untouched", "239.1.2.3", updated.pinnedGroup)
                assertEquals("239.9.9.9", updated.patchGroup)
            }
        }
    }

    @Test
    fun `choosing cleartext keeps the current wire format`() {
        val openManet =
            ChannelMulticastConfig(
                channelName = "ops-1",
                wireFormat = WireFormat.OPENMANET_RTP,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.1.2.3",
                pinnedPort = 5007,
            )
        assertEquals(openManet, openManet.withPrimaryCryptoPolicy(CryptoPolicy.CLEARTEXT))
        val native = ChannelMulticastConfig.defaultFor("ops-1").withPrimaryCryptoPolicy(CryptoPolicy.CLEARTEXT)
        assertEquals(WireFormat.XV_NATIVE, native.wireFormat)
        assertNull(native.validate())
    }
}
