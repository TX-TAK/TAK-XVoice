package com.atakmap.android.xv.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mesh-policy invariant on real SharedPreferences: XV-native legs are
 * always REQUIRED (on read, on write, and on disk after the once-only
 * migration), cleartext lives only on interop legs, and the retired
 * `xv_crypto_policy_<channel>` prefs are deleted.
 */
@RunWith(RobolectricTestRunner::class)
class XvSettingsCryptoPolicyMigrationTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settings = XvSettings { prefs }
    }

    // Write a config's JSON straight to disk, bypassing persist-time
    // normalization, to model data left by an older build.
    private fun storeRaw(vararg cfgs: ChannelMulticastConfig) {
        val set = prefs.getStringSet(MULTICAST_KEY, emptySet())!!.toMutableSet()
        cfgs.forEach { set.add(it.toJson()) }
        prefs.edit().putStringSet(MULTICAST_KEY, set).commit()
    }

    private fun rawPolicies(): Map<String, String> =
        prefs.getStringSet(MULTICAST_KEY, emptySet())!!.associate {
            val o = JSONObject(it)
            o.getString("channel") to o.getString("cryptoPolicy")
        }

    @Test
    fun `migration rewrites every stored XV-native policy to REQUIRED and drops legacy prefs`() {
        storeRaw(
            ChannelMulticastConfig("chosen", cryptoPolicy = CryptoPolicy.PREFERRED),
            ChannelMulticastConfig("clear", cryptoPolicy = CryptoPolicy.CLEARTEXT),
        )
        prefs.edit().putString("xv_crypto_policy_chosen", "PREFERRED").commit()

        assertEquals(2, settings.migrateMeshCryptoPolicy().normalized.size)

        assertEquals(mapOf("chosen" to "REQUIRED", "clear" to "REQUIRED"), rawPolicies())
        assertTrue(prefs.all.keys.none { it.startsWith("xv_crypto_policy_") })
    }

    @Test
    fun `migration runs once, but reads stay normalized afterwards`() {
        storeRaw(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.PREFERRED))
        assertEquals(1, settings.migrateMeshCryptoPolicy().normalized.size)

        // An older build writes PREFERRED again after the flag is set.
        storeRaw(ChannelMulticastConfig("late", cryptoPolicy = CryptoPolicy.PREFERRED))
        assertTrue(settings.migrateMeshCryptoPolicy().normalized.isEmpty())
        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("late").cryptoPolicy)
    }

    @Test
    fun `persist normalizes an XV-native policy to REQUIRED`() {
        settings.persistChannelMulticastConfig(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.CLEARTEXT))
        assertEquals("REQUIRED", rawPolicies()["ops"])
    }

    @Test
    fun `interop primary is split into encrypted primary plus cleartext patch`() {
        storeRaw(
            ChannelMulticastConfig(
                "vx",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.1.1.1",
                pinnedPort = 5007,
            ),
        )
        settings.migrateMeshCryptoPolicy()
        val cfg = settings.effectiveChannelMulticastConfig("vx")
        assertEquals(WireFormat.XV_NATIVE, cfg.wireFormat)
        assertEquals(CryptoPolicy.REQUIRED, cfg.cryptoPolicy)
        assertEquals("239.1.1.1", cfg.patchGroup)
        assertEquals(WireFormat.VX_COMPAT, cfg.patchWireFormat)
        assertEquals(CryptoPolicy.CLEARTEXT, cfg.patchCryptoPolicy)
    }

    @Test
    fun `channel without a stored config reads the encrypted-only default`() {
        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("fresh").cryptoPolicy)
    }

    companion object {
        // XvSettings' private PREF_CHANNEL_MULTICAST key.
        private const val MULTICAST_KEY = "channel_multicast_configs"
    }

    @Test
    fun `unconvertible interop primary is reported on every run, then converted once its patch is cleared`() {
        val busy =
            ChannelMulticastConfig(
                "busy",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.1.1.1",
                pinnedPort = 5007,
                patchGroup = "239.2.2.2",
                patchPort = 5010,
                patchWireFormat = WireFormat.OPENMANET_RTP,
            )
        storeRaw(busy)

        assertEquals(listOf("busy"), settings.migrateMeshCryptoPolicy().unconvertibleInteropPrimaries)
        assertEquals(
            "still reported after the once-only rewrite",
            listOf("busy"),
            settings.migrateMeshCryptoPolicy().unconvertibleInteropPrimaries,
        )
        assertEquals(WireFormat.VX_COMPAT, settings.effectiveChannelMulticastConfig("busy").wireFormat)

        // Operator clears the patch in the gear dialog and saves.
        settings.persistChannelMulticastConfig(busy.copy(patchGroup = null, patchPort = null))

        val cfg = settings.effectiveChannelMulticastConfig("busy")
        assertEquals(WireFormat.XV_NATIVE, cfg.wireFormat)
        assertEquals(CryptoPolicy.REQUIRED, cfg.cryptoPolicy)
        assertEquals("239.1.1.1", cfg.patchGroup)
        assertTrue(settings.migrateMeshCryptoPolicy().unconvertibleInteropPrimaries.isEmpty())
    }

    @Test
    fun `unpinned interop primary left by the retired VX prompt becomes encrypted XV-native`() {
        storeRaw(ChannelMulticastConfig("vx", wireFormat = WireFormat.VX_COMPAT, cryptoPolicy = CryptoPolicy.CLEARTEXT))
        val report = settings.migrateMeshCryptoPolicy()
        assertTrue(report.unconvertibleInteropPrimaries.isEmpty())
        val cfg = settings.effectiveChannelMulticastConfig("vx")
        assertEquals(WireFormat.XV_NATIVE, cfg.wireFormat)
        assertEquals(CryptoPolicy.REQUIRED, cfg.cryptoPolicy)
        assertEquals(null, cfg.patchGroup)
        assertEquals(null, cfg.validate())
    }
}
