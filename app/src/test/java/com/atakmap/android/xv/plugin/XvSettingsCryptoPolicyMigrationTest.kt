package com.atakmap.android.xv.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.atakmap.android.xv.transport.multicast.ChannelMulticastConfig
import com.atakmap.android.xv.transport.multicast.CryptoPolicy
import com.atakmap.android.xv.transport.multicast.WireFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The retired `xv_crypto_policy_<channel>` prefs are folded into the
 * per-channel configs once and then deleted, so the config is the only
 * crypto posture left on disk.
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

    @Test
    fun `downgraded channel is restored and every legacy key is removed`() {
        settings.persistChannelMulticastConfig(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.PREFERRED))
        settings.persistChannelMulticastConfig(
            ChannelMulticastConfig(
                "interop",
                wireFormat = WireFormat.VX_COMPAT,
                cryptoPolicy = CryptoPolicy.CLEARTEXT,
                pinnedGroup = "239.1.1.1",
                pinnedPort = 5007,
            ),
        )
        prefs.edit()
            .putString("xv_crypto_policy_ops", "REQUIRED")
            .putString("xv_crypto_policy_interop", "CLEARTEXT")
            .putString("xv_crypto_policy_orphan", "REQUIRED")
            .commit()

        assertEquals(1, settings.migrateLegacyCryptoPolicyPrefs())

        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("ops").cryptoPolicy)
        assertEquals(CryptoPolicy.CLEARTEXT, settings.effectiveChannelMulticastConfig("interop").cryptoPolicy)
        assertNull("orphan pref is not materialized into an override", settings.channelMulticastConfigFor("orphan"))
        assertTrue(prefs.all.keys.none { it.startsWith("xv_crypto_policy_") })
    }

    @Test
    fun `migration is idempotent`() {
        settings.persistChannelMulticastConfig(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.PREFERRED))
        prefs.edit().putString("xv_crypto_policy_ops", "REQUIRED").commit()

        assertEquals(1, settings.migrateLegacyCryptoPolicyPrefs())
        assertEquals(0, settings.migrateLegacyCryptoPolicyPrefs())
        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("ops").cryptoPolicy)
    }

    @Test
    fun `channel without a stored config reads the encrypted-only default`() {
        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("fresh").cryptoPolicy)
    }

    @Test
    fun `stored old-default PREFERRED is upgraded once, a later explicit choice sticks`() {
        settings.persistChannelMulticastConfig(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.PREFERRED))

        assertEquals(1, settings.migrateFailoverDefaultToRequired())
        assertEquals(CryptoPolicy.REQUIRED, settings.effectiveChannelMulticastConfig("ops").cryptoPolicy)

        settings.persistChannelMulticastConfig(ChannelMulticastConfig("ops", cryptoPolicy = CryptoPolicy.PREFERRED))
        assertEquals(0, settings.migrateFailoverDefaultToRequired())
        assertEquals(CryptoPolicy.PREFERRED, settings.effectiveChannelMulticastConfig("ops").cryptoPolicy)
    }
}
