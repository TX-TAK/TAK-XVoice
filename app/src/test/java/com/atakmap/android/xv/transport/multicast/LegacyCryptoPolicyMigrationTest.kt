package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCryptoPolicyMigrationTest {
    private fun native(
        name: String,
        policy: CryptoPolicy,
    ) = ChannelMulticastConfig(channelName = name, cryptoPolicy = policy)

    private fun interopPrimary(
        name: String,
        format: WireFormat,
        patch: Boolean = false,
    ) = ChannelMulticastConfig(
        channelName = name,
        wireFormat = format,
        cryptoPolicy = CryptoPolicy.CLEARTEXT,
        pinnedGroup = "239.1.1.1",
        pinnedPort = 5007,
        patchGroup = if (patch) "239.2.2.2" else null,
        patchPort = if (patch) 5010 else null,
        patchWireFormat = WireFormat.OPENMANET_RTP,
    )

    @Test
    fun `every XV-native config ends up REQUIRED, including explicit PREFERRED`() {
        val report =
            LegacyCryptoPolicyMigration.plan(
                listOf(
                    native("a", CryptoPolicy.PREFERRED),
                    native("b", CryptoPolicy.CLEARTEXT),
                    native("c", CryptoPolicy.REQUIRED),
                ),
            )
        assertEquals(listOf(native("a", CryptoPolicy.REQUIRED), native("b", CryptoPolicy.REQUIRED)), report.normalized)
        assertTrue(report.unconvertibleInteropPrimaries.isEmpty())
    }

    @Test
    fun `interop primary with a free patch slot becomes encrypted primary plus cleartext patch`() {
        for (format in listOf(WireFormat.VX_COMPAT, WireFormat.OPENMANET_RTP)) {
            val report = LegacyCryptoPolicyMigration.plan(listOf(interopPrimary("vx", format)))
            val converted = report.normalized.single()
            assertEquals(WireFormat.XV_NATIVE, converted.wireFormat)
            assertEquals(CryptoPolicy.REQUIRED, converted.cryptoPolicy)
            assertNull("primary moves to the derived endpoint", converted.pinnedGroup)
            assertEquals("239.1.1.1", converted.patchGroup)
            assertEquals(5007, converted.patchPort)
            assertEquals("framing is kept on the patch", format, converted.patchWireFormat)
            assertEquals(CryptoPolicy.CLEARTEXT, converted.patchCryptoPolicy)
            assertNull(converted.validate())
        }
    }

    @Test
    fun `interop primary whose patch slot is taken is left alone and reported`() {
        val cfg = interopPrimary("busy", WireFormat.VX_COMPAT, patch = true)
        val report = LegacyCryptoPolicyMigration.plan(listOf(cfg))
        assertTrue(report.normalized.isEmpty())
        assertEquals(listOf("busy"), report.unconvertibleInteropPrimaries)
        assertEquals(cfg, cfg.meshPolicyNormalized())
    }

    @Test
    fun `normalization is idempotent`() {
        val once = interopPrimary("vx", WireFormat.VX_COMPAT).meshPolicyNormalized()
        assertEquals(once, once.meshPolicyNormalized())
        assertTrue(LegacyCryptoPolicyMigration.plan(listOf(once)).normalized.isEmpty())
    }

    @Test
    fun `failover default is encrypted only, interop patch stays cleartext`() {
        val d = ChannelMulticastConfig.defaultFor("ops")
        assertEquals(CryptoPolicy.REQUIRED, d.cryptoPolicy)
        assertEquals(WireFormat.XV_NATIVE, d.wireFormat)
        assertEquals(CryptoPolicy.CLEARTEXT, d.patchCryptoPolicy)
    }
}
