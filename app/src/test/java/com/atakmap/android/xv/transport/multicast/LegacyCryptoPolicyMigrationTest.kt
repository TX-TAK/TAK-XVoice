package com.atakmap.android.xv.transport.multicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCryptoPolicyMigrationTest {
    private fun cfg(
        name: String,
        policy: CryptoPolicy,
        format: WireFormat = WireFormat.XV_NATIVE,
    ) = ChannelMulticastConfig(
        channelName = name,
        wireFormat = format,
        cryptoPolicy = policy,
        pinnedGroup = if (format.isInterop) "239.1.1.1" else null,
        pinnedPort = if (format.isInterop) 5007 else null,
    )

    @Test
    fun `silently downgraded REQUIRED channel is restored`() {
        val fixes = LegacyCryptoPolicyMigration.plan(mapOf("ops" to "REQUIRED"), listOf(cfg("ops", CryptoPolicy.PREFERRED)))
        assertEquals(listOf(cfg("ops", CryptoPolicy.REQUIRED)), fixes)
    }

    @Test
    fun `agreeing stores need no fix`() {
        val fixes =
            LegacyCryptoPolicyMigration.plan(
                mapOf("a" to "REQUIRED", "b" to "PREFERRED", "c" to "CLEARTEXT"),
                listOf(
                    cfg("a", CryptoPolicy.REQUIRED),
                    cfg("b", CryptoPolicy.PREFERRED),
                    cfg("c", CryptoPolicy.CLEARTEXT, WireFormat.VX_COMPAT),
                ),
            )
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `an explicit cleartext config wins over a stale REQUIRED pref`() {
        val fixes =
            LegacyCryptoPolicyMigration.plan(
                mapOf("ops" to "REQUIRED"),
                listOf(cfg("ops", CryptoPolicy.CLEARTEXT, WireFormat.VX_COMPAT)),
            )
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `a stricter config is never loosened by the pref`() {
        val fixes = LegacyCryptoPolicyMigration.plan(mapOf("ops" to "PREFERRED"), listOf(cfg("ops", CryptoPolicy.REQUIRED)))
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `orphan prefs and garbage values are ignored`() {
        val fixes =
            LegacyCryptoPolicyMigration.plan(
                mapOf("gone" to "REQUIRED", "ops" to "NOT_A_POLICY"),
                listOf(cfg("ops", CryptoPolicy.PREFERRED)),
            )
        assertTrue(fixes.isEmpty())
    }
}
