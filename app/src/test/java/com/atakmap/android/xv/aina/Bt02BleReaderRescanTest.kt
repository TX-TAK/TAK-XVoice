package com.atakmap.android.xv.aina

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [Bt02BleReader]'s address re-resolution across failure rounds:
 * scan windows progress across rounds (unfiltered fallback, balanced
 * mode) instead of restarting at window 0 every time, a device that
 * answers but is not recognised is not rescanned, and a confirmed
 * subscription starts the next outage from the beginning.
 */
@RunWith(RobolectricTestRunner::class)
class Bt02BleReaderRescanTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Scan(
        val filters: List<ScanFilter>?,
        val mode: Int,
    )

    private class Harness(
        context: Context,
        recognised: Boolean,
    ) {
        val scans = mutableListOf<Scan>()
        val gatts = mutableListOf<BluetoothGatt>()
        val states = mutableListOf<Boolean>()
        var callback: BluetoothGattCallback? = null
        val reader =
            Bt02BleReader(
                appContext = context,
                onEvent = { _, _ -> },
                onConnectionState = { states += it },
                leScanner = {
                    object : Bt02BleReader.LeScanner {
                        override fun startScan(
                            filters: List<ScanFilter>?,
                            settings: ScanSettings,
                            callback: ScanCallback,
                        ) {
                            scans += Scan(filters, settings.scanMode)
                        }

                        override fun stopScan(callback: ScanCallback) = Unit
                    }
                },
                gattConnector =
                AinaBleReader.GattConnector { _, _, _, cb ->
                    callback = cb
                    bt02Gatt(recognised).also { gatts += it }
                },
                adapterEnabled = { true },
            )

        val cb get() = checkNotNull(callback)
        val current get() = gatts.last()
    }

    private fun idle(seconds: Long) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(seconds))
    }

    private fun harness(recognised: Boolean = true): Harness {
        val h = Harness(context, recognised)
        h.reader.connectBt02(leDevice())
        idle(1)
        return h
    }

    // The cached address does not answer: the attempt fails without ever
    // reaching STATE_CONNECTED.
    private fun failUnreached(h: Harness) {
        h.cb.onConnectionStateChange(h.current, 133, BluetoothProfile.STATE_DISCONNECTED)
        idle(70)
    }

    // Fail until the reader hands the next round to a rescan.
    private fun failUntilScan(h: Harness) {
        val before = h.scans.size
        repeat(8) {
            if (h.scans.size > before) return
            failUnreached(h)
        }
    }

    @Test
    fun `scan windows progress across re-resolution rounds`() {
        val h = harness()
        failUntilScan(h)
        assertEquals(1, h.scans.size)
        assertNotNull("first window is name-filtered", h.scans[0].filters)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, h.scans[0].mode)
        idle(11) // window misses → back to address retries
        failUntilScan(h)
        assertEquals(2, h.scans.size)
        assertNull("second window is the unfiltered fallback (screen on)", h.scans[1].filters)
        idle(11)
        failUntilScan(h)
        assertEquals(3, h.scans.size)
        assertEquals("later windows drop to balanced", ScanSettings.SCAN_MODE_BALANCED, h.scans[2].mode)
        h.reader.disconnect()
    }

    @Test
    fun `a device that answers but is not recognised is not rescanned`() {
        val h = harness(recognised = false)
        repeat(GattSetup.UNRECOGNISED_LIMIT + 1) {
            val g = h.current
            h.cb.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            h.cb.onServicesDiscovered(g, BluetoothGatt.GATT_SUCCESS)
            idle(70)
        }
        assertTrue("address answered — nothing to re-resolve", h.scans.isEmpty())
        h.reader.disconnect()
    }

    @Test
    fun `a confirmed subscription restarts the scan progression`() {
        val h = harness()
        failUntilScan(h)
        idle(11)
        // Back on the cached address, and this time it works.
        val g = h.current
        h.cb.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        h.cb.onServicesDiscovered(g, BluetoothGatt.GATT_SUCCESS)
        h.cb.onDescriptorWrite(g, cccdOf(g), BluetoothGatt.GATT_SUCCESS)
        assertEquals(true, h.states.last())
        // Later outage: the link drops, the address stops answering.
        h.cb.onConnectionStateChange(g, 8, BluetoothProfile.STATE_DISCONNECTED)
        idle(70)
        failUntilScan(h)
        assertEquals(2, h.scans.size)
        assertNotNull("new outage starts at window 0 (filtered)", h.scans[1].filters)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, h.scans[1].mode)
        h.reader.disconnect()
    }

    private fun cccdOf(g: BluetoothGatt): BluetoothGattDescriptor =
        g.services
            .single()
            .characteristics
            .single()
            .getDescriptor(BitmaskGattPttReader.CCCD_UUID)

    private fun leDevice(): BluetoothDevice {
        val dev = mockk<BluetoothDevice>(relaxed = true)
        every { dev.address } returns "AA:BB:CC:DD:EE:FF"
        every { dev.name } returns "B02PTT-0000"
        every { dev.type } returns BluetoothDevice.DEVICE_TYPE_LE
        return dev
    }

    private companion object {
        fun bt02Gatt(recognised: Boolean): BluetoothGatt {
            val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
            every { cccd.uuid } returns BitmaskGattPttReader.CCCD_UUID
            val ch = mockk<BluetoothGattCharacteristic>(relaxed = true)
            every { ch.uuid } returns Bt02BleReader.BT02_BUTTON_CHARACTERISTIC_UUID
            every { ch.properties } returns BluetoothGattCharacteristic.PROPERTY_NOTIFY
            every { ch.getDescriptor(BitmaskGattPttReader.CCCD_UUID) } returns cccd
            every { cccd.characteristic } returns ch
            val svc = mockk<BluetoothGattService>(relaxed = true)
            every { svc.characteristics } returns if (recognised) listOf(ch) else emptyList()
            val gatt = mockk<BluetoothGatt>(relaxed = true)
            every { gatt.services } returns listOf(svc)
            every { gatt.discoverServices() } returns true
            every { gatt.setCharacteristicNotification(any(), true) } returns true
            every { gatt.writeDescriptor(any(), any<ByteArray>()) } returns BluetoothGatt.GATT_SUCCESS
            return gatt
        }
    }

    @Test
    fun `scanning for the companion peripheral counts as working on the Classic device`() {
        val h = Harness(context, recognised = true)
        val classic = mockk<BluetoothDevice>(relaxed = true)
        every { classic.address } returns "AA:BB:CC:DD:EE:01"
        every { classic.name } returns "B02PTT-0000"
        every { classic.type } returns BluetoothDevice.DEVICE_TYPE_CLASSIC
        h.reader.connectBt02(classic)
        idle(1)
        assertEquals(1, h.scans.size)
        assertTrue(h.reader.isWorkingOn("AA:BB:CC:DD:EE:01"))
        idle(11) // window missed, next one scheduled
        assertTrue("between scan windows too", h.reader.isWorkingOn("AA:BB:CC:DD:EE:01"))
        h.reader.disconnect()
        assertTrue("torn down", !h.reader.isWorkingOn("AA:BB:CC:DD:EE:01"))
    }
}
