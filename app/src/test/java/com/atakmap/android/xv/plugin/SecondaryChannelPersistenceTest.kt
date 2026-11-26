package com.atakmap.android.xv.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Channel 2 persistence: the PREF_SECONDARY_CHANNEL round-trip on a real
 * SharedPreferences, and the restore / persist-on-move rules.
 */
@RunWith(RobolectricTestRunner::class)
class SecondaryChannelPersistenceTest {
    private lateinit var settings: XvSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx
            .getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        settings = XvSettings { ctx.getSharedPreferences(XvSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    }

    @Test
    fun `fresh install has channel 2 off`() {
        assertEquals("", settings.persistedSecondaryChannel())
        assertNull(SecondaryChannelPersistence.restore(settings.persistedSecondaryChannel()))
    }

    @Test
    fun `channel 2 round-trips independently of channel 1`() {
        settings.persistPrimaryChannel("Alpha")
        settings.persistSecondaryChannel("Bravo")
        assertEquals("Alpha", settings.persistedPrimaryChannel())
        assertEquals("Bravo", settings.persistedSecondaryChannel())
        assertEquals("Bravo", SecondaryChannelPersistence.restore(settings.persistedSecondaryChannel()))
    }

    @Test
    fun `swap persists both channels exchanged`() {
        settings.persistPrimaryChannel("Alpha")
        settings.persistSecondaryChannel("Bravo")
        // Completed swap: slot-0 onChannelChanged + swap outcome.
        settings.persistPrimaryChannel("Bravo")
        settings.persistSecondaryChannel("Alpha")
        assertEquals("Bravo", settings.persistedPrimaryChannel())
        assertEquals("Alpha", settings.persistedSecondaryChannel())
    }

    @Test
    fun `clearing channel 2 persists off`() {
        settings.persistSecondaryChannel("Bravo")
        settings.persistSecondaryChannel("")
        assertNull(SecondaryChannelPersistence.restore(settings.persistedSecondaryChannel()))
    }

    @Test
    fun `temp private-call channel is never restored`() {
        assertNull(SecondaryChannelPersistence.restore("TAK PRIVATE - Alpha-Bravo"))
        assertNull(SecondaryChannelPersistence.restore("   "))
        assertEquals("Charlie", SecondaryChannelPersistence.restore("  Charlie "))
    }

    @Test
    fun `slot-1 moves persist except temp channels and swap legs`() {
        assertTrue(SecondaryChannelPersistence.shouldPersistMove("Charlie", swapInFlight = false))
        assertTrue(SecondaryChannelPersistence.shouldPersistMove("Lobby", swapInFlight = false))
        assertFalse(SecondaryChannelPersistence.shouldPersistMove("Charlie", swapInFlight = true))
        assertFalse(SecondaryChannelPersistence.shouldPersistMove("TAK PRIVATE - x", swapInFlight = false))
        assertFalse(SecondaryChannelPersistence.shouldPersistMove(null, swapInFlight = false))
    }
}
