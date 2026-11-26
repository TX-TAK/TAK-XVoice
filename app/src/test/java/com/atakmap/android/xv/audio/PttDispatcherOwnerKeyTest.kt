package com.atakmap.android.xv.audio

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Held-state keying matrix for [PttDispatcher]: keys are
 * (reader owner, source, slot), not source alone.
 *
 * Field shapes these cover:
 *  - primary speaker-mic and external button on the same reader class
 *    (two AINA V2s, two SPP V1s) — they share a [PttSource];
 *  - PTT1 and PTT2 on one speaker-mic — same owner and source,
 *    different slot;
 *  - an external puck re-attaching (ACL_CONNECTED) while the
 *    speaker-mic is mid-burst — the external slot is torn down and
 *    respawned, and that must not end the speaker-mic's burst;
 *  - a torn-down reader delivering a late down edge — must be dropped
 *    rather than left as a held key nothing will release.
 */
@RunWith(RobolectricTestRunner::class)
class PttDispatcherOwnerKeyTest {
    private lateinit var txController: TxController
    private var latchedMode = false

    private fun dispatcher(): PttDispatcher =
        PttDispatcher(
            txController = txController,
            statusTones = null,
            latchedModeEnabled = { latchedMode },
            momentaryTimeoutSec = { 0 },
            latchedTimeoutSec = { 0 },
        )

    @Before
    fun setup() {
        txController = mockk(relaxed = true)
        latchedMode = false
    }

    @Test
    fun `external teardown does not end a burst held on the primary with the same source`() {
        val d = dispatcher()
        val primary = PttInputOwner("primary-v2")
        val external = PttInputOwner("external-v2")
        d.down(0, PttSource.AINA_V2, primary)
        d.forgetOwner(external, retire = true)
        verify(exactly = 0) { txController.stop() }
        d.up(0, PttSource.AINA_V2, primary)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `external teardown while both held keeps TX until primary releases`() {
        val d = dispatcher()
        val primary = PttInputOwner("primary-spp")
        val external = PttInputOwner("external-spp")
        d.down(0, PttSource.AINA_V1, primary)
        d.down(0, PttSource.AINA_V1, external)
        d.forgetOwner(external, retire = true)
        verify(exactly = 0) { txController.stop() }
        d.up(0, PttSource.AINA_V1, primary)
        verify(exactly = 1) { txController.stop() }
        verify(exactly = 1) { txController.start(slot = 0) }
    }

    @Test
    fun `releasing the external does not release the primary on the same source`() {
        val d = dispatcher()
        val primary = PttInputOwner("primary-ble-hid")
        val external = PttInputOwner("external-ble-hid")
        d.down(0, PttSource.PRYME_BLE, primary)
        d.down(0, PttSource.PRYME_BLE, external)
        d.up(0, PttSource.PRYME_BLE, external)
        verify(exactly = 0) { txController.stop() }
        d.up(0, PttSource.PRYME_BLE, primary)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `PTT2 tap during held PTT1 on one device does not end the burst`() {
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.down(1, PttSource.AINA_V2, mic)
        d.up(1, PttSource.AINA_V2, mic)
        verify(exactly = 0) { txController.stop() }
        verify(exactly = 1) { txController.start(slot = 0) }
        d.up(0, PttSource.AINA_V2, mic)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `up on a slot that is not held is a no-op while another key is held`() {
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.up(1, PttSource.AINA_V2, mic)
        verify(exactly = 0) { txController.stop() }
    }

    @Test
    fun `transport drop forgets the owner without retiring it`() {
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.forgetOwner(mic)
        verify(exactly = 1) { txController.stop() }
        assertFalse(mic.retired)
        // Same reader reconnects — its next press is real.
        d.down(0, PttSource.AINA_V2, mic)
        verify(exactly = 2) { txController.start(slot = 0) }
    }

    @Test
    fun `teardown retires the owner and a late down from it is ignored`() {
        val d = dispatcher()
        val stale = PttInputOwner("primary-spp")
        d.forgetOwner(stale, retire = true)
        assertTrue(stale.retired)
        d.down(0, PttSource.AINA_V1, stale)
        verify(exactly = 0) { txController.start(any()) }
    }

    @Test
    fun `stale owner late down cannot keep a new reader's burst alive`() {
        val d = dispatcher()
        val old = PttInputOwner("external-v2#old")
        val fresh = PttInputOwner("external-v2#new")
        d.forgetOwner(old, retire = true)
        d.down(0, PttSource.AINA_V2, fresh)
        d.down(0, PttSource.AINA_V2, old) // late edge from torn-down reader
        d.up(0, PttSource.AINA_V2, fresh)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `retired owner cannot toggle a latched burst off`() {
        latchedMode = true
        val d = dispatcher()
        val live = PttInputOwner("primary-v2")
        val stale = PttInputOwner("external-v2")
        d.forgetOwner(stale, retire = true)
        d.down(0, PttSource.AINA_V2, live)
        assertTrue(d.isLatched())
        d.down(0, PttSource.AINA_V2, stale)
        assertTrue(d.isLatched())
        verify(exactly = 0) { txController.stop() }
    }

    @Test
    fun `forgetSource still clears every owner of that source`() {
        val d = dispatcher()
        val primary = PttInputOwner("primary-v2")
        val external = PttInputOwner("external-v2")
        d.down(0, PttSource.AINA_V2, primary)
        d.down(0, PttSource.AINA_V2, external)
        d.forgetSource(PttSource.AINA_V2)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `BT02 and Pryme are distinct sources for forgetSource`() {
        val d = dispatcher()
        d.down(0, PttSource.INRICO_BT02, PttInputOwner("primary-bt02"))
        d.forgetSource(PttSource.PRYME_BLE)
        verify(exactly = 0) { txController.stop() }
        d.forgetSource(PttSource.INRICO_BT02)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `on-screen press keeps TX when a speaker-mic reader is torn down`() {
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.down(0, PttSource.ON_SCREEN)
        d.forgetOwner(mic, retire = true)
        verify(exactly = 0) { txController.stop() }
        d.up(0, PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `on-screen channel 2 press releases on its own up`() {
        val d = dispatcher()
        d.down(1, PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.start(slot = 1) }
        d.up(1, PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `on-screen release with the wrong slot still ends the burst`() {
        // Belt and braces for the stuck-mic bug: the UI used to release
        // every press as slot 0. One finger, one on-screen input.
        val d = dispatcher()
        d.down(1, PttSource.ON_SCREEN)
        d.up(0, PttSource.ON_SCREEN)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `Active Key release clears its key whatever the slot`() {
        val d = dispatcher()
        d.down(0, PttSource.SAMSUNG_ACTIVE_KEY)
        d.up(1, PttSource.SAMSUNG_ACTIVE_KEY)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `on-screen wildcard release does not touch a held speaker-mic`() {
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.down(1, PttSource.ON_SCREEN)
        d.up(0, PttSource.ON_SCREEN)
        verify(exactly = 0) { txController.stop() }
        d.up(0, PttSource.AINA_V2, mic)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `back-compat up without source clears every owner`() {
        val d = dispatcher()
        d.down(0, PttSource.AINA_V2, PttInputOwner("primary-v2"))
        d.down(0, PttSource.PRYME_BLE, PttInputOwner("external-ble-hid"))
        d.down(1, PttSource.ON_SCREEN)
        d.up(0)
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `latched burst survives forgetOwner of its reader and toggles off from another input`() {
        // Latched TX is owned by latchedActive, not by the held set: a
        // reader teardown must not end it, and the next press toggles off.
        latchedMode = true
        val d = dispatcher()
        val mic = PttInputOwner("primary-v2")
        d.down(0, PttSource.AINA_V2, mic)
        d.forgetOwner(mic, retire = true)
        assertTrue(d.isLatched())
        verify(exactly = 0) { txController.stop() }
        d.down(0, PttSource.ON_SCREEN)
        assertFalse(d.isLatched())
        verify(exactly = 1) { txController.stop() }
    }

    @Test
    fun `down racing a teardown never leaves TX running with nothing held`() {
        // Thread A presses on a reader while thread B tears that reader
        // down. Whichever wins, the end state must be "not transmitting".
        repeat(200) {
            val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
            val tx = mockk<TxController>()
            io.mockk.every { tx.start(any()) } answers { calls += "start" }
            io.mockk.every { tx.stop() } answers { calls += "stop" }
            val d =
                PttDispatcher(
                    txController = tx,
                    statusTones = null,
                    latchedModeEnabled = { false },
                    momentaryTimeoutSec = { 0 },
                    latchedTimeoutSec = { 0 },
                )
            val owner = PttInputOwner("primary-spp")
            val go = java.util.concurrent.CountDownLatch(1)
            val a = Thread {
                go.await()
                d.down(0, PttSource.AINA_V1, owner)
            }
            val b = Thread {
                go.await()
                d.forgetOwner(owner, retire = true)
            }
            a.start()
            b.start()
            go.countDown()
            a.join()
            b.join()
            val last = synchronized(calls) { calls.lastOrNull() }
            assertTrue("TX left running after teardown: $calls", last == null || last == "stop")
        }
    }

    @Test
    fun `concurrent releases on two devices end the burst exactly when both are up`() {
        repeat(200) {
            val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
            val tx = mockk<TxController>()
            io.mockk.every { tx.start(any()) } answers { calls += "start" }
            io.mockk.every { tx.stop() } answers { calls += "stop" }
            val d =
                PttDispatcher(
                    txController = tx,
                    statusTones = null,
                    latchedModeEnabled = { false },
                    momentaryTimeoutSec = { 0 },
                    latchedTimeoutSec = { 0 },
                )
            val one = PttInputOwner("primary-v2")
            val two = PttInputOwner("external-ble-hid")
            d.down(0, PttSource.AINA_V2, one)
            val go = java.util.concurrent.CountDownLatch(1)
            // Thread A releases device one while thread B presses then
            // holds device two: B's press must keep TX on.
            val a = Thread {
                go.await()
                d.up(0, PttSource.AINA_V2, one)
            }
            val b = Thread {
                go.await()
                d.down(0, PttSource.PRYME_BLE, two)
            }
            a.start()
            b.start()
            go.countDown()
            a.join()
            b.join()
            val last = synchronized(calls) { calls.last() }
            assertTrue("device two still held but TX stopped: $calls", last == "start")
            d.up(0, PttSource.PRYME_BLE, two)
            assertTrue(synchronized(calls) { calls.last() } == "stop")
        }
    }
}
