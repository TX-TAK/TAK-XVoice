package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Connect-lifecycle coverage for [AinaBleReader]:
 *
 *  - "connected" is reported only once the button CCCD write is
 *    confirmed — not at STATE_CONNECTED, not at service discovery;
 *  - the direct-retry counter and autoConnect arm reset only on that
 *    confirmation, so a device that links up but never subscribes
 *    keeps backing off instead of retrying fast forever;
 *  - callbacks from a closed / replaced BluetoothGatt are ignored;
 *  - setup failures (missing CCCD, rejected write) and a null
 *    connectGatt go back through the reconnect path;
 *  - security failures surface "needs re-pair" once.
 */
@RunWith(RobolectricTestRunner::class)
class AinaBleReaderRetryCountTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Harness(
        context: Context,
        gattFactory: () -> BluetoothGatt?,
    ) {
        val gatts = mutableListOf<BluetoothGatt?>()
        val states = mutableListOf<Boolean>()
        val repairs = mutableListOf<String>()
        var callback: BluetoothGattCallback? = null
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { _, _ -> },
                onConnectionState = { states += it },
                onNeedsRepair = { repairs += it },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, cb ->
                    callback = cb
                    gattFactory().also { gatts += it }
                },
                adapterEnabled = { true },
            )

        val cb get() = checkNotNull(callback)
        val current get() = checkNotNull(gatts.last())
    }

    private fun idle(seconds: Long = 30) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(seconds))
    }

    private fun harness(gattFactory: () -> BluetoothGatt? = { mockGatt() }): Harness {
        val h = Harness(context, gattFactory)
        h.reader.connect(mockDevice())
        idle()
        h.states.clear()
        return h
    }

    private fun failCurrent(h: Harness) {
        h.cb.onConnectionStateChange(h.current, 133, BluetoothProfile.STATE_DISCONNECTED)
        idle()
    }

    private fun subscribe(h: Harness) {
        val g = h.current
        h.cb.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        h.cb.onServicesDiscovered(g, BluetoothGatt.GATT_SUCCESS)
    }

    private fun cccdOf(g: BluetoothGatt): BluetoothGattDescriptor =
        g
            .getService(AinaBleReader.SERVICE_UUID)!!
            .getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID)!!
            .getDescriptor(AinaBleReader.CCCD_UUID)!!

    @Test
    fun `connected is reported only after the CCCD write is confirmed`() {
        val h = harness()
        subscribe(h)
        assertTrue("no connected edge before the subscription is confirmed", h.states.none { it })
        h.cb.onDescriptorWrite(h.current, cccdOf(h.current), BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(true), h.states)
        h.reader.disconnect()
    }

    @Test
    fun `retry counter resets on confirmed subscription, not on connect or discovery`() {
        val h = harness()
        repeat(5) { failCurrent(h) }
        assertNotEquals(0, h.reader.directRetryCountForTest())
        assertTrue(h.reader.autoConnectArmedForTest())
        val before = h.reader.directRetryCountForTest()

        subscribe(h)
        assertEquals("CONNECTED + discovery must not reset", before, h.reader.directRetryCountForTest())

        h.cb.onDescriptorWrite(h.current, cccdOf(h.current), BluetoothGatt.GATT_SUCCESS)
        assertEquals(0, h.reader.directRetryCountForTest())
        assertFalse(h.reader.autoConnectArmedForTest())
        h.reader.disconnect()
    }

    @Test
    fun `callbacks from a replaced gatt are ignored and the stale handle is closed`() {
        val h = harness()
        val stale = h.current
        failCurrent(h)
        val fresh = h.current
        assertTrue(stale !== fresh)

        h.cb.onConnectionStateChange(stale, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        verify(exactly = 0) { stale.discoverServices() }
        verify(atLeast = 1) { stale.close() }
        h.cb.onServicesDiscovered(stale, BluetoothGatt.GATT_SUCCESS)
        h.cb.onDescriptorWrite(stale, cccdOf(stale), BluetoothGatt.GATT_SUCCESS)
        assertTrue("stale handle must not report connected", h.states.none { it })
        h.reader.disconnect()
    }

    @Test
    fun `missing CCCD reconnects instead of sitting half-configured`() {
        val h = harness { mockGatt(withCccd = false) }
        val first = h.current
        subscribe(h)
        verify(atLeast = 1) { first.close() }
        idle()
        assertTrue("expected a fresh connectGatt, got ${h.gatts.size}", h.gatts.size >= 2)
        h.reader.disconnect()
    }

    @Test
    fun `rejected CCCD write reconnects`() {
        val h = harness { mockGatt(writeResult = 201) }
        subscribe(h)
        idle()
        assertTrue(h.gatts.size >= 2)
        assertTrue(h.states.none { it })
        h.reader.disconnect()
    }

    @Test
    fun `null connectGatt handle schedules a retry instead of crashing`() {
        var calls = 0
        val h =
            harness {
                calls++
                if (calls == 1) null else mockGatt()
            }
        idle()
        assertTrue("expected a retry after the null handle, got $calls calls", calls >= 2)
        h.reader.disconnect()
    }

    @Test
    fun `auth failure on CCCD write surfaces needs-repair once and arms slow retry`() {
        val h = harness()
        subscribe(h)
        h.cb.onDescriptorWrite(h.current, cccdOf(h.current), GattSetup.GATT_INSUFFICIENT_AUTHENTICATION)
        assertEquals(1, h.repairs.size)
        assertTrue(h.reader.autoConnectArmedForTest())
        idle(seconds = 150)
        subscribe(h)
        h.cb.onDescriptorWrite(h.current, cccdOf(h.current), GattSetup.GATT_AUTH_FAIL)
        assertEquals("needs-repair must not repeat within one connect()", 1, h.repairs.size)
        h.reader.disconnect()
    }

    @Test
    fun `held PTT is released when the link drops after subscribing`() {
        val events = mutableListOf<Pair<AinaButton, Boolean>>()
        var cb: BluetoothGattCallback? = null
        val g = mockGatt()
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { b, d -> events += b to d },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, c ->
                    cb = c
                    g
                },
                adapterEnabled = { true },
            )
        reader.connect(mockDevice())
        idle()
        val c = checkNotNull(cb)
        c.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        c.onServicesDiscovered(g, BluetoothGatt.GATT_SUCCESS)
        val ch = g.getService(AinaBleReader.SERVICE_UUID)!!.getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID)!!
        val press = byteArrayOf(AinaButton.PTT.bitMask.toByte())
        // Before the CCCD is confirmed, presses do not count.
        c.onCharacteristicChanged(g, ch, press)
        assertTrue(events.isEmpty())
        c.onDescriptorWrite(g, cccdOf(g), BluetoothGatt.GATT_SUCCESS)
        c.onCharacteristicChanged(g, ch, press)
        assertEquals(listOf(AinaButton.PTT to true), events)
        c.onConnectionStateChange(g, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(AinaButton.PTT to false, events.last())
        reader.disconnect()
    }

    @Test
    fun `setup watchdog reconnects a link stuck before subscription`() {
        val h = harness()
        val first = h.current
        h.cb.onConnectionStateChange(first, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        idle(seconds = 16)
        verify(atLeast = 1) { first.close() }
        idle()
        assertTrue(h.gatts.size >= 2)
        h.reader.disconnect()
    }

    @Test
    fun `connect on a disposed reader opens nothing`() {
        var n = 0
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { _, _ -> },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, _ ->
                    n++
                    mockGatt()
                },
                adapterEnabled = { true },
            )
        reader.dispose()
        reader.connect(mockDevice())
        idle()
        assertEquals(0, n)
    }

    @Test
    fun `missing BLUETOOTH_CONNECT is reported once and retries stop`() {
        var n = 0
        val problems = mutableListOf<String>()
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { _, _ -> },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, _ ->
                    n++
                    throw SecurityException("BLUETOOTH_CONNECT")
                },
                onProblem = { problems += it },
                adapterEnabled = { true },
            )
        reader.connect(mockDevice())
        idle(seconds = 600)
        assertEquals(1, n)
        assertEquals(1, problems.size)
        reader.disconnect()
    }

    @Test
    fun `isConnected follows the confirmed subscription`() {
        val h = harness()
        subscribe(h)
        assertFalse(h.reader.isConnected())
        h.cb.onDescriptorWrite(h.current, cccdOf(h.current), BluetoothGatt.GATT_SUCCESS)
        assertTrue(h.reader.isConnected())
        h.cb.onConnectionStateChange(h.current, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(h.reader.isConnected())
        h.reader.disconnect()
    }

    @Test
    fun `a press racing a link drop is released after it, never left held`() {
        val g = mockGatt()
        val inDispatch = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<Pair<AinaButton, Boolean>>())
        var cb: BluetoothGattCallback? = null
        val reader =
            AinaBleReader(
                context = context,
                onEvent = { b, d ->
                    events += b to d
                    if (d) {
                        inDispatch.countDown()
                        proceed.await(5, TimeUnit.SECONDS)
                    }
                },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, c ->
                    cb = c
                    g
                },
                adapterEnabled = { true },
            )
        reader.connect(mockDevice())
        idle()
        val c = checkNotNull(cb)
        c.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        c.onServicesDiscovered(g, BluetoothGatt.GATT_SUCCESS)
        c.onDescriptorWrite(g, cccdOf(g), BluetoothGatt.GATT_SUCCESS)
        val ch = g.getService(AinaBleReader.SERVICE_UUID)!!.getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID)!!
        val press = thread { c.onCharacteristicChanged(g, ch, byteArrayOf(AinaButton.PTT.bitMask.toByte())) }
        assertTrue(inDispatch.await(5, TimeUnit.SECONDS))
        val drop = thread { c.onConnectionStateChange(g, 8, BluetoothProfile.STATE_DISCONNECTED) }
        drop.join(300)
        assertTrue("teardown must wait for the in-flight press", drop.isAlive)
        proceed.countDown()
        press.join(5_000)
        drop.join(5_000)
        assertEquals(listOf(AinaButton.PTT to true, AinaButton.PTT to false), events.toList())
        reader.disconnect()
    }

    private fun mockDevice(): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns "AA:BB:CC:DD:EE:FF"
        every { dev.name } returns "APTT V2 (mock)"
        return dev
    }

    private fun mockGatt(
        withCccd: Boolean = true,
        writeResult: Int = BluetoothGatt.GATT_SUCCESS,
    ): BluetoothGatt {
        val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { cccd.uuid } returns AinaBleReader.CCCD_UUID
        val ch = mockk<BluetoothGattCharacteristic>(relaxed = true)
        every { ch.uuid } returns AinaBleReader.BUTTON_CHAR_UUID
        every { ch.getDescriptor(AinaBleReader.CCCD_UUID) } returns if (withCccd) cccd else null
        val svc = mockk<BluetoothGattService>(relaxed = true)
        every { svc.getCharacteristic(AinaBleReader.BUTTON_CHAR_UUID) } returns ch
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.getService(AinaBleReader.SERVICE_UUID) } returns svc
        every { gatt.setCharacteristicNotification(ch, true) } returns true
        every { gatt.discoverServices() } returns true
        every { gatt.writeDescriptor(cccd, any<ByteArray>()) } returns writeResult
        return gatt
    }
}
