package com.atakmap.android.xv.aina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattSetupTest {
    @Test
    fun `auth statuses are 5, 15 and 137 only`() {
        assertTrue(GattSetup.isAuthFailure(5))
        assertTrue(GattSetup.isAuthFailure(15))
        assertTrue(GattSetup.isAuthFailure(137))
        assertFalse(GattSetup.isAuthFailure(133))
        assertFalse(GattSetup.isAuthFailure(8))
        assertFalse(GattSetup.isAuthFailure(0))
        assertFalse(GattSetup.isAuthFailure(null))
    }

    @Test
    fun `reopen delay enforces the settle gap after close`() {
        assertEquals(0L, GattSetup.reopenDelayMs(lastCloseMs = 0L, nowMs = 5_000L))
        assertEquals(120L, GattSetup.reopenDelayMs(lastCloseMs = 1_000L, nowMs = 1_000L))
        assertEquals(20L, GattSetup.reopenDelayMs(lastCloseMs = 1_000L, nowMs = 1_100L))
        assertEquals(0L, GattSetup.reopenDelayMs(lastCloseMs = 1_000L, nowMs = 1_120L))
        assertEquals(0L, GattSetup.reopenDelayMs(lastCloseMs = 1_000L, nowMs = 9_000L))
    }

    @Test
    fun `sequencer issues one write at a time in order`() {
        val seq = CccdSubscriptionSequencer(listOf("a", "b", "c"))
        assertEquals(CccdSubscriptionSequencer.Step.Write("a"), seq.start())
        assertEquals(CccdSubscriptionSequencer.Step.Write("b"), seq.onWritten("a"))
        assertEquals(CccdSubscriptionSequencer.Step.Write("c"), seq.onWritten("b"))
        assertEquals(CccdSubscriptionSequencer.Step.Done, seq.onWritten("c"))
        assertEquals(3, seq.confirmed)
    }

    @Test
    fun `sequencer ignores confirmations for anything but the in-flight write`() {
        val seq = CccdSubscriptionSequencer(listOf("a", "b"))
        seq.start()
        assertEquals(CccdSubscriptionSequencer.Step.Ignore, seq.onWritten("b"))
        assertEquals(CccdSubscriptionSequencer.Step.Ignore, seq.onWritten("zzz"))
        assertEquals(0, seq.confirmed)
        assertEquals(CccdSubscriptionSequencer.Step.Write("b"), seq.onWritten("a"))
    }

    @Test
    fun `empty subscription is reported, not silently connected`() {
        assertEquals(
            CccdSubscriptionSequencer.Step.NothingToSubscribe,
            CccdSubscriptionSequencer(emptyList<String>()).start(),
        )
    }

    @Test
    fun `single target completes after one confirmation`() {
        val seq = CccdSubscriptionSequencer(listOf("only"))
        assertEquals(CccdSubscriptionSequencer.Step.Write("only"), seq.start())
        assertEquals(CccdSubscriptionSequencer.Step.Done, seq.onWritten("only"))
    }

    @Test
    fun `BT02 rescans by name after three failed attempts`() {
        assertFalse(Bt02BleReader.shouldRescanByName(2))
        assertTrue(Bt02BleReader.shouldRescanByName(3))
        assertTrue(Bt02BleReader.shouldRescanByName(10))
    }

    @Test
    fun `BT02 discovery alternates name-filtered and unfiltered windows`() {
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-AB12", 0))
        assertFalse(Bt02BleReader.useNameFilter("B02PTT-AB12", 1))
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-AB12", 2))
        assertFalse(Bt02BleReader.useNameFilter(null, 0))
        assertFalse(Bt02BleReader.useNameFilter("Some Headset", 0))
    }
}
