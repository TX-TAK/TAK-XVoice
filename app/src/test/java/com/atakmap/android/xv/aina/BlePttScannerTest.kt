package com.atakmap.android.xv.aina

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [BlePttScanner.matchesKnownPttDevice] — the client-side
 * allow-list that decides which results an UNFILTERED platform scan
 * surfaces. Pure function, so it is pinned here without the BT stack.
 */
class BlePttScannerTest {
    private val batteryService: UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    @Test
    fun `HM-10 service UUID matches regardless of name`() {
        // Pryme BT-PTT-Z / PTT-Z01 advertise service 0xFFE0 and often
        // no local name in the scan record.
        assertTrue(
            BlePttScanner.matchesKnownPttDevice(null, setOf(BlePttScanner.HM10_SERVICE_UUID)),
        )
    }

    @Test
    fun `PTT-Z01 advertised name matches`() {
        assertTrue(BlePttScanner.matchesKnownPttDevice("PTT-Z01", emptySet()))
    }

    @Test
    fun `Inrico BT02 speaker-mic is excluded from the BLE button scanner`() {
        // The BT02 is a primary speaker-mic paired via Classic Bluetooth
        // (just like an AINA), not a button-only BLE puck — its companion
        // BLE link is discovered automatically by Bt02BleReader.
        assertFalse(BlePttScanner.matchesKnownPttDevice("B02PTT-1234", setOf(batteryService)))
    }

    @Test
    fun `unrelated BLE device does not match`() {
        // A random peripheral advertising only Battery, or nothing, must
        // NOT be surfaced — otherwise the unfiltered scan would flood the
        // picker with every nearby device.
        assertFalse(BlePttScanner.matchesKnownPttDevice("Galaxy Buds", setOf(batteryService)))
        assertFalse(BlePttScanner.matchesKnownPttDevice(null, emptySet()))
        assertFalse(BlePttScanner.matchesKnownPttDevice("", emptySet()))
    }
}
