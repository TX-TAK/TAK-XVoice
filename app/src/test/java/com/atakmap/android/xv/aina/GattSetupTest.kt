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
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-0000", 0))
        assertFalse(Bt02BleReader.useNameFilter("B02PTT-0000", 1))
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-0000", 2))
        assertFalse(Bt02BleReader.useNameFilter(null, 0))
        assertFalse(Bt02BleReader.useNameFilter("Some Headset", 0))
    }

    @Test
    fun `HCI stale-bond reasons are distinct from GATT op statuses`() {
        assertTrue(GattSetup.isHciStaleBond(5))
        assertTrue(GattSetup.isHciStaleBond(6))
        assertTrue(GattSetup.isHciStaleBond(61))
        assertFalse("HCI 15 is unacceptable BD_ADDR", GattSetup.isHciStaleBond(15))
        assertFalse(GattSetup.isHciStaleBond(8))
        assertFalse(GattSetup.isHciStaleBond(19))
        assertTrue(GattSetup.isGattOpAuthFailure(15))
        assertFalse(GattSetup.isGattOpAuthFailure(61))
    }

    @Test
    fun `retry cadence and caps`() {
        assertEquals(2_000L, GattSetup.retryDelayMs(0, false, false, 0))
        assertEquals(16_000L, GattSetup.retryDelayMs(3, false, false, 0))
        assertEquals(16_000L, GattSetup.retryDelayMs(9, false, false, 0))
        assertEquals(5_000L, GattSetup.retryDelayMs(5, true, false, 0))
        assertEquals(60_000L, GattSetup.retryDelayMs(5, true, false, 0, autoConnectWaits = false))
        assertEquals(
            "armed + fast failure: no 5 s spin",
            60_000L,
            GattSetup.retryDelayMs(5, true, false, 0, GattSetup.autoConnectWaits(true, failedFast = true))
        )
        assertEquals(
            "not armed: fast failure keeps the direct backoff",
            2_000L,
            GattSetup.retryDelayMs(0, false, false, 0, autoConnectWaits = false),
        )
        assertEquals(120_000L, GattSetup.retryDelayMs(0, true, true, 0))
        assertEquals(300_000L, GattSetup.retryDelayMs(0, false, false, GattSetup.UNRECOGNISED_LIMIT))
        assertEquals(300_000L, GattSetup.retryDelayMs(0, true, true, GattSetup.UNRECOGNISED_LIMIT))
        assertTrue(GattSetup.shouldArmAutoConnect(4, false))
        assertTrue(GattSetup.shouldArmAutoConnect(0, true))
        assertFalse(GattSetup.shouldArmAutoConnect(3, false))
    }

    @Test
    fun `operator notice throttle`() {
        assertTrue(GattSetup.shouldSurface(0L, 1_000L, 600_000L))
        assertFalse(GattSetup.shouldSurface(1_000L, 300_000L, 600_000L))
        assertTrue(GattSetup.shouldSurface(1_000L, 601_000L, 600_000L))
    }

    @Test
    fun `BT02 rescan is skipped for bond failures and alternates with address retries`() {
        assertFalse(Bt02BleReader.shouldRescanByName(5, authFailure = true, round = 0))
        assertTrue(Bt02BleReader.shouldRescanByName(5, authFailure = false, round = 0))
        assertFalse(Bt02BleReader.shouldRescanByName(5, authFailure = false, round = 1))
        assertTrue(Bt02BleReader.shouldRescanByName(5, authFailure = false, round = 2))
    }

    @Test
    fun `BT02 discovery backs off and drops out of low latency`() {
        assertEquals(15_000L, Bt02BleReader.discoveryRetryDelayMs(1))
        assertEquals(30_000L, Bt02BleReader.discoveryRetryDelayMs(2))
        assertEquals(60_000L, Bt02BleReader.discoveryRetryDelayMs(3))
        assertEquals(300_000L, Bt02BleReader.discoveryRetryDelayMs(10))
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY, Bt02BleReader.scanModeFor(0))
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY, Bt02BleReader.scanModeFor(1))
        assertEquals(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED, Bt02BleReader.scanModeFor(2))
    }

    @Test
    fun `BT02 uses only name-filtered windows while the screen is off`() {
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-0000", 1, screenOn = false))
        assertTrue(Bt02BleReader.useNameFilter("B02PTT-0000", 3, screenOn = false))
        assertFalse(Bt02BleReader.useNameFilter(null, 0, screenOn = false))
    }
}
