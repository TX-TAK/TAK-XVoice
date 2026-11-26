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
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Connect-lifecycle coverage for [BitmaskGattPttReader]: CCCD writes
 * are issued one at a time, "connected" waits for every confirmation,
 * the button-characteristic restriction keeps phantom notify chars out,
 * and setup failures reconnect.
 */
@RunWith(RobolectricTestRunner::class)
class BitmaskGattPttReaderLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val service: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val button: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val extra: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    private class Fixture(
        val gatt: BluetoothGatt,
        val cccds: Map<UUID, BluetoothGattDescriptor>,
        val chars: Map<UUID, BluetoothGattCharacteristic>,
    )

    private fun fixture(
        charUuids: List<UUID>,
        writeResult: Int = BluetoothGatt.GATT_SUCCESS,
    ): Fixture {
        val cccds = mutableMapOf<UUID, BluetoothGattDescriptor>()
        val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()
        val svc = mockk<BluetoothGattService>(relaxed = true)
        every { svc.uuid } returns service
        for (u in charUuids) {
            val ch = mockk<BluetoothGattCharacteristic>(relaxed = true)
            val d = mockk<BluetoothGattDescriptor>(relaxed = true)
            every { d.uuid } returns BitmaskGattPttReader.CCCD_UUID
            every { d.characteristic } returns ch
            every { ch.uuid } returns u
            every { ch.properties } returns BluetoothGattCharacteristic.PROPERTY_NOTIFY
            every { ch.getDescriptor(BitmaskGattPttReader.CCCD_UUID) } returns d
            cccds[u] = d
            chars[u] = ch
        }
        every { svc.characteristics } returns chars.values.toList()
        val gatt = mockk<BluetoothGatt>(relaxed = true)
        every { gatt.services } returns listOf(svc)
        every { gatt.getService(service) } returns svc
        every { gatt.discoverServices() } returns true
        every { gatt.setCharacteristicNotification(any(), true) } returns true
        every { gatt.writeDescriptor(any(), any<ByteArray>()) } returns writeResult
        return Fixture(gatt, cccds, chars)
    }

    private class Run(
        val reader: BitmaskGattPttReader,
        val callbacks: MutableList<BluetoothGattCallback>,
        val connects: () -> Int,
        val states: MutableList<Boolean>,
        val events: MutableList<Pair<AinaButton, Boolean>>,
    ) {
        val cb get() = callbacks.last()
    }

    private fun start(
        fixtures: List<Fixture>,
        buttonChars: Map<UUID, List<UUID>> = emptyMap(),
    ): Run {
        val callbacks = mutableListOf<BluetoothGattCallback>()
        var n = 0
        val states = mutableListOf<Boolean>()
        val events = mutableListOf<Pair<AinaButton, Boolean>>()
        repairs.clear()
        problems.clear()
        val reader =
            BitmaskGattPttReader(
                context = context,
                config =
                BitmaskGattPttReader.Config(
                    tag = "Test",
                    candidateServiceUuids = listOf(service),
                    decodeMask = PrymeBleReader::decodePrymeMask,
                    buttonCharacteristicUuids = buttonChars,
                ),
                onEvent = { b, d -> events += b to d },
                onConnectionState = { states += it },
                onNeedsRepair = { repairs += it },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, cb ->
                    callbacks += cb
                    fixtures[(n++).coerceAtMost(fixtures.size - 1)].gatt
                },
                onProblem = { problems += it },
                adapterEnabled = { true },
            )
        reader.connect(mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns "AA:BB:CC:DD:EE:FF" })
        idle()
        states.clear()
        return Run(reader, callbacks, { n }, states, events)
    }

    private val repairs = mutableListOf<String>()
    private val problems = mutableListOf<String>()

    private fun subscribeAll(
        r: Run,
        f: Fixture,
    ) {
        connectAndDiscover(r, f)
        for (d in f.cccds.values) r.cb.onDescriptorWrite(f.gatt, d, BluetoothGatt.GATT_SUCCESS)
    }

    private fun idle() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
    }

    private fun connectAndDiscover(
        r: Run,
        f: Fixture,
    ) {
        r.cb.onConnectionStateChange(f.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        r.cb.onServicesDiscovered(f.gatt, BluetoothGatt.GATT_SUCCESS)
    }

    @Test
    fun `CCCD writes are issued one at a time and connected waits for all`() {
        val f = fixture(listOf(button, extra))
        val r = start(listOf(f))
        connectAndDiscover(r, f)
        verify(exactly = 1) { f.gatt.writeDescriptor(any(), any<ByteArray>()) }
        assertTrue(r.states.none { it })

        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(button), BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 2) { f.gatt.writeDescriptor(any(), any<ByteArray>()) }
        assertTrue(r.states.none { it })

        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(extra), BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(true), r.states)
        r.reader.disconnect()
    }

    @Test
    fun `known button characteristic restricts the subscription`() {
        val f = fixture(listOf(button, extra))
        val r = start(listOf(f), buttonChars = mapOf(service to listOf(button)))
        connectAndDiscover(r, f)
        verify(exactly = 0) { f.gatt.setCharacteristicNotification(f.chars.getValue(extra), true) }
        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(button), BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(true), r.states)
        // A non-zero byte on the unsubscribed extra char must not key
        // PTT (decodePrymeMask reads any non-zero byte as held).
        r.cb.onCharacteristicChanged(f.gatt, f.chars.getValue(extra), byteArrayOf(0x41))
        assertTrue(r.events.isEmpty())
        // The button char still works.
        r.cb.onCharacteristicChanged(f.gatt, f.chars.getValue(button), byteArrayOf(0x01))
        assertEquals(listOf(AinaButton.PTT to true), r.events)
        r.reader.disconnect()
    }

    @Test
    fun `rejected descriptor write reconnects without reporting connected`() {
        val bad = fixture(listOf(button), writeResult = 201)
        val good = fixture(listOf(button))
        val r = start(listOf(bad, good))
        connectAndDiscover(r, bad)
        verify(atLeast = 1) { bad.gatt.close() }
        idle()
        assertTrue(r.connects() >= 2)
        assertTrue(r.states.none { it })
        r.reader.disconnect()
    }

    @Test
    fun `failed descriptor write after partial subscription reconnects`() {
        val f = fixture(listOf(button, extra))
        val r = start(listOf(f, fixture(listOf(button, extra))))
        connectAndDiscover(r, f)
        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(button), BluetoothGatt.GATT_SUCCESS)
        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(extra), 133)
        verify(atLeast = 1) { f.gatt.close() }
        assertTrue(r.states.none { it })
        r.reader.disconnect()
    }

    @Test
    fun `service discovery failure reconnects`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f, fixture(listOf(button))))
        r.cb.onConnectionStateChange(f.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        r.cb.onServicesDiscovered(f.gatt, 129)
        idle()
        assertTrue(r.connects() >= 2)
        r.reader.disconnect()
    }

    @Test
    fun `stale gatt notifications are ignored`() {
        val first = fixture(listOf(button))
        val second = fixture(listOf(button))
        val r = start(listOf(first, second))
        r.cb.onConnectionStateChange(first.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        idle()
        r.cb.onCharacteristicChanged(first.gatt, first.chars.getValue(button), byteArrayOf(0x01))
        assertTrue(r.events.isEmpty())
        r.reader.disconnect()
    }

    @Test
    fun `selectButtonCharacteristicUuids restricts only listed services`() {
        val available = listOf(button, extra)
        assertEquals(
            listOf(button),
            BitmaskGattPttReader.selectButtonCharacteristicUuids(service, available, mapOf(service to listOf(button))),
        )
        assertEquals(
            available,
            BitmaskGattPttReader.selectButtonCharacteristicUuids(service, available, emptyMap()),
        )
        assertEquals(
            emptyList<UUID>(),
            BitmaskGattPttReader.selectButtonCharacteristicUuids(service, listOf(extra), mapOf(service to listOf(button))),
        )
    }

    @Test
    fun `button notifications before the subscription is confirmed are dropped`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f))
        connectAndDiscover(r, f)
        r.cb.onCharacteristicChanged(f.gatt, f.chars.getValue(button), byteArrayOf(0x01))
        assertTrue(r.events.isEmpty())
        r.reader.disconnect()
    }

    @Test
    fun `held PTT is released when the link drops`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f, fixture(listOf(button))))
        subscribeAll(r, f)
        r.cb.onCharacteristicChanged(f.gatt, f.chars.getValue(button), byteArrayOf(0x01))
        assertEquals(listOf(AinaButton.PTT to true), r.events)
        r.cb.onConnectionStateChange(f.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(listOf(AinaButton.PTT to true, AinaButton.PTT to false), r.events)
        r.reader.disconnect()
    }

    @Test
    fun `held PTT is released on intentional disconnect`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f))
        subscribeAll(r, f)
        r.cb.onCharacteristicChanged(f.gatt, f.chars.getValue(button), byteArrayOf(0x01))
        r.reader.disconnect()
        assertEquals(AinaButton.PTT to false, r.events.last())
    }

    @Test
    fun `setup watchdog tears down a link that never subscribes`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f, fixture(listOf(button))))
        r.cb.onConnectionStateChange(f.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        // Discovery never answers.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        verify(atLeast = 1) { f.gatt.close() }
        assertEquals(false, r.states.lastOrNull())
        idle()
        assertTrue(r.connects() >= 2)
        r.reader.disconnect()
    }

    @Test
    fun `high connection priority is requested only once subscribed`() {
        val f = fixture(listOf(button))
        val r = start(listOf(f))
        connectAndDiscover(r, f)
        verify(exactly = 0) { f.gatt.requestConnectionPriority(any()) }
        r.cb.onDescriptorWrite(f.gatt, f.cccds.getValue(button), BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 1) { f.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        r.reader.disconnect()
    }

    @Test
    fun `unrecognised device is reported once and backs off to minutes`() {
        val fixtures =
            (1..8).map {
                fixture(listOf(button)).also { f -> every { f.gatt.getService(service) } returns null }
            }
        val r = start(fixtures)
        repeat(GattSetup.UNRECOGNISED_LIMIT) { i ->
            connectAndDiscover(r, fixtures[i])
            idle()
        }
        assertEquals(1, problems.size)
        val connectsAtLimit = r.connects()
        // The next retry is minutes away, not seconds.
        idle()
        assertEquals(connectsAtLimit, r.connects())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
        assertTrue(r.connects() > connectsAtLimit)
        r.reader.disconnect()
    }

    @Test
    fun `HCI disconnect reason 15 is not a bond failure but 61 is`() {
        val f1 = fixture(listOf(button))
        val r = start(listOf(f1, fixture(listOf(button))))
        r.cb.onConnectionStateChange(f1.gatt, 15, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(repairs.isEmpty())
        r.reader.disconnect()

        val f = fixture(listOf(button))
        val r2 = start(listOf(f))
        r2.cb.onConnectionStateChange(f.gatt, GattSetup.HCI_MIC_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, repairs.size)
        r2.reader.disconnect()
    }

    @Test
    fun `reconnects pause while the adapter is off`() {
        val f = fixture(listOf(button))
        var n = 0
        val reader =
            BitmaskGattPttReader(
                context = context,
                config =
                BitmaskGattPttReader.Config(
                    tag = "Test",
                    candidateServiceUuids = listOf(service),
                    decodeMask = PrymeBleReader::decodePrymeMask,
                ),
                onEvent = { _, _ -> },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, _ ->
                    n++
                    f.gatt
                },
                adapterEnabled = { false },
            )
        reader.connect(mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns "AA:BB:CC:DD:EE:FF" })
        idle()
        assertEquals(0, n)
        reader.disconnect()
    }
}
