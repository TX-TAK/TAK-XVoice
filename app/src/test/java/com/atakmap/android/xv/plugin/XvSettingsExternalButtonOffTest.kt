package com.atakmap.android.xv.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Bluetooth button "Off" choice persists and is independent of the saved MAC. */
@RunWith(RobolectricTestRunner::class)
class XvSettingsExternalButtonOffTest {
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        settings = XvSettings { ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    }

    @Test
    fun `defaults to not off`() {
        assertFalse(settings.externalButtonOff())
    }

    @Test
    fun `off survives clearing the saved button and is cleared by picking one`() {
        settings.persistExternalButtonMac("AA:BB:CC:DD:EE:FF")
        settings.persistExternalButtonOff(true)
        settings.persistExternalButtonMac(null)
        assertTrue(settings.externalButtonOff())
        settings.persistExternalButtonOff(false)
        assertFalse(settings.externalButtonOff())
    }
}
