package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Generation / cancellation coverage for [AinaSppReader]:
 *  - disconnect() must abort an RFCOMM connect() that is still in
 *    flight (by closing the pending socket) and the aborted attempt
 *    must not fire a connected edge or keep retrying;
 *  - a connect() that completes after disconnect() (stack ignored the
 *    close) must close its socket and stay silent;
 *  - a throwing onConnectionState(true) handler must not leak the
 *    socket;
 *  - the respawn guard keeps a live V1 link only for the SPP kinds.
 */
@RunWith(RobolectricTestRunner::class)
class AinaSppReaderGenerationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `disconnect closes an in-flight connect and suppresses its edges`() {
        val connectEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val sock = mockk<BluetoothSocket>(relaxed = true)
        every { sock.connect() } answers {
            connectEntered.countDown()
            // Real BluetoothSocket.connect() unblocks with IOException
            // when another thread closes the socket.
            awaitUninterruptibly(closed)
            throw IOException("socket closed")
        }
        every { sock.close() } answers { closed.countDown() }
        val creates = AtomicInteger(0)
        val ups = AtomicInteger(0)
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> if (up) ups.incrementAndGet() },
                socketFactory =
                AinaSppReader.SocketFactory { _, _, _ ->
                    creates.incrementAndGet()
                    sock
                },
            )
        reader.connect(device())
        assertTrue("connect() never entered", connectEntered.await(2, TimeUnit.SECONDS))
        reader.disconnect()
        assertTrue("pending socket was not closed by disconnect()", closed.await(2, TimeUnit.SECONDS))
        Thread.sleep(1_500) // longer than one in-burst backoff
        assertEquals("cancelled attempt must not retry", 1, creates.get())
        assertEquals("cancelled attempt must not report connected", 0, ups.get())
        assertFalse(reader.isConnectedTo(MAC))
    }

    @Test
    fun `connect completing after disconnect closes the stale socket silently`() {
        val connectEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedAfterConnect = CountDownLatch(1)
        val connectReturned = CountDownLatch(1)
        val sock = mockk<BluetoothSocket>(relaxed = true)
        every { sock.connect() } answers {
            connectEntered.countDown()
            // Stack that ignores close() during connect.
            // Real connect() ignores Thread.interrupt(); so must the fake.
            awaitUninterruptibly(release)
            connectReturned.countDown()
        }
        every { sock.close() } answers {
            if (connectReturned.count == 0L) closedAfterConnect.countDown()
        }
        val ups = AtomicInteger(0)
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> if (up) ups.incrementAndGet() },
                socketFactory = AinaSppReader.SocketFactory { _, _, _ -> sock },
            )
        reader.connect(device())
        assertTrue(connectEntered.await(2, TimeUnit.SECONDS))
        reader.disconnect()
        release.countDown()
        assertTrue(
            "socket that connected after disconnect() must be closed",
            closedAfterConnect.await(2, TimeUnit.SECONDS),
        )
        assertEquals(0, ups.get())
        assertFalse(reader.isConnectedTo(MAC))
    }

    @Test
    fun `throwing connected handler does not leak the socket`() {
        val closed = CountDownLatch(1)
        val sock = mockk<BluetoothSocket>(relaxed = true)
        every { sock.connect() } answers { }
        every { sock.inputStream } returns EofInputStream()
        every { sock.close() } answers { closed.countDown() }
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> check(!up) { "plugin bug" } },
                socketFactory = AinaSppReader.SocketFactory { _, _, _ -> sock },
            )
        reader.connect(device())
        assertTrue("socket leaked after handler threw", closed.await(2, TimeUnit.SECONDS))
        reader.disconnect()
    }

    @Test
    fun `isConnectedTo reports the live link and clears on disconnect`() {
        val up = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val sock = mockk<BluetoothSocket>(relaxed = true)
        every { sock.connect() } answers { }
        every { sock.inputStream } returns HoldingInputStream(hold)
        every { sock.close() } answers { hold.countDown() }
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { u -> if (u) up.countDown() },
                socketFactory = AinaSppReader.SocketFactory { _, _, _ -> sock },
            )
        reader.connect(device())
        assertTrue(up.await(2, TimeUnit.SECONDS))
        assertTrue(reader.isConnectedTo(MAC.lowercase()))
        assertFalse(reader.isConnectedTo("11:22:33:44:55:66"))
        reader.disconnect()
        assertFalse(reader.isConnectedTo(MAC))
    }

    @Test
    fun `respawn guard keeps a live link only for SPP kinds`() {
        assertTrue(AinaSppReader.shouldKeepLiveReader(true, "v1"))
        assertTrue(AinaSppReader.shouldKeepLiveReader(true, "spp"))
        assertFalse(AinaSppReader.shouldKeepLiveReader(true, "v2"))
        assertFalse(AinaSppReader.shouldKeepLiveReader(true, "ble-hid"))
        assertFalse(AinaSppReader.shouldKeepLiveReader(true, null))
        assertFalse(AinaSppReader.shouldKeepLiveReader(false, "v1"))
    }

    @Test
    fun `reconnect queued by an old generation never starts a second read thread`() {
        // Gen 1 exhausts its burst and queues a reconnect on the main
        // looper. Before it fires, connect() starts gen 2, whose socket
        // blocks in connect(). Firing the stale reconnect must not open a
        // second concurrent attempt.
        val failing = mockk<BluetoothSocket>(relaxed = true)
        every { failing.connect() } throws IOException("peer not there")
        val blockingEntered = AtomicInteger(0)
        val release = CountDownLatch(1)
        val blocking = mockk<BluetoothSocket>(relaxed = true)
        every { blocking.connect() } answers {
            blockingEntered.incrementAndGet()
            awaitUninterruptibly(release)
            throw IOException("closed")
        }
        every { blocking.close() } answers { release.countDown() }
        val exhausted = CountDownLatch(1)
        var phase = 1
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { up -> if (!up && phase == 1) exhausted.countDown() },
                socketFactory = AinaSppReader.SocketFactory { _, _, _ -> if (phase == 1) failing else blocking },
            )
        reader.connect(device())
        assertTrue("gen 1 never exhausted", exhausted.await(5, TimeUnit.SECONDS))
        phase = 2
        reader.connect(device())
        // Let gen 2 reach its blocking connect(), then fire anything the
        // main looper has queued (gen 1's reconnect).
        val deadline = System.currentTimeMillis() + 2_000
        while (blockingEntered.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofSeconds(60))
        Thread.sleep(300)
        assertEquals("only gen 2's attempt may be in connect()", 1, blockingEntered.get())
        reader.disconnect()
    }

    @Test
    fun `fresh reader connect does not announce a disconnect`() {
        val edges = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val sock = mockk<BluetoothSocket>(relaxed = true)
        val hold = CountDownLatch(1)
        every { sock.connect() } answers { awaitUninterruptibly(hold) }
        every { sock.close() } answers { hold.countDown() }
        val reader =
            AinaSppReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { edges += it },
                socketFactory = AinaSppReader.SocketFactory { _, _, _ -> sock },
            )
        reader.connect(device())
        Thread.sleep(200)
        assertTrue("no false edge for a reader that had no session: $edges", edges.none { !it })
        reader.disconnect()
    }

    private fun device(): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns MAC
        every { dev.name } returns "APTT V1 (mock)"
        every { dev.bondState } returns BluetoothDevice.BOND_BONDED
        every { dev.createRfcommSocketToServiceRecord(any<UUID>()) } returns mockk(relaxed = true)
        every { dev.createInsecureRfcommSocketToServiceRecord(any<UUID>()) } returns mockk(relaxed = true)
        return dev
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            try {
                latch.await(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // keep waiting, as a real RFCOMM connect would
            }
        }
    }

    private class EofInputStream : InputStream() {
        override fun read(): Int = -1
    }

    private class HoldingInputStream(
        private val gate: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int {
            gate.await(5, TimeUnit.SECONDS)
            return -1
        }
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:FF"
    }
}
