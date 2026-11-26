package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.UUID

/**
 * BLE GATT button reader for the **Inrico BT02** Zello-compatible
 * Bluetooth speaker-mic.
 *
 * The BT02 does NOT do Classic Bluetooth pairing — it connects as a
 * BLE peripheral (random address, no bond), so Android's HID path
 * never sees it and the companion app has to drive the GATT link and
 * subscribe to the button-state characteristic itself, the same way
 * [PrymeBleReader] / [AinaBleReader] do.
 *
 * ## Wire format (on-device probe 2026-11-21, nRF Connect + logcat)
 *
 * Button state arrives on notify characteristic
 * [BT02_BUTTON_CHARACTERISTIC_UUID] as a **single enumerated code
 * byte** — NOT a Zello-style held-buttons bitmask:
 *
 *   | Byte   | Button                         |
 *   |--------|--------------------------------|
 *   | `0x00` | (all released)                 |
 *   | `0x01` | PTT (primary)                  |
 *   | `0x03` | Top button                     |
 *   | `0x08` | Emergency                      |
 *
 * The giveaway that this is a code and not a bitmask is the top
 * button reporting `0x03`: as a bitmask that would read as
 * "PTT (0x01) + emergency (0x02) held at once", which is not what the
 * hardware does. So [ZelloBleReader.decodeZelloMask] is the wrong
 * decoder here (it would misfire the top button and silently drop the
 * `0x08` emergency, which Zello's convention leaves unmapped). The
 * BT02 gets its own [decodeBt02Code] that treats the byte as an
 * opaque code.
 *
 * Volume up / down are handled locally on the device (speaker volume)
 * and never reach the GATT button characteristic — nothing to map.
 *
 * ## Button mapping
 *
 *   `0x01` → [AinaButton.PTT]   (primary PTT)
 *   `0x08` → [AinaButton.PTTE]  (emergency PTT)
 *   `0x03` → intentionally unmapped — the top button has no assigned
 *            mission yet (a channel / talk-group cycle was considered
 *            and deferred). Mapping it is a deliberate future change,
 *            not an accident; Bt02BleReaderTest pins that it emits
 *            nothing today.
 *
 * ## Characteristic-first matching
 *
 * Unlike the service-matched readers, the BT02 config supplies no
 * candidate service UUID — the probe showed the button characteristic
 * buried under one of several vendor services, and the exact parent
 * isn't worth pinning. Instead [BitmaskGattPttReader] is told the
 * characteristic UUID directly via `candidateCharacteristicUuids`, so
 * it finds and subscribes to [BT02_BUTTON_CHARACTERISTIC_UUID]
 * wherever it lives in the GATT tree.
 */
@SuppressLint("MissingPermission")
class Bt02BleReader(
    private val appContext: Context,
    onEvent: (AinaButton, isDown: Boolean) -> Unit,
    onConnectionState: (Boolean) -> Unit = {},
    onNeedsRepair: (String) -> Unit = {},
    onProblem: (String) -> Unit = {},
) : BitmaskGattPttReader(
    context = appContext,
    config = BT02_CONFIG,
    onEvent = onEvent,
    onConnectionState = onConnectionState,
    onNeedsRepair = onNeedsRepair,
    onProblem = onProblem,
) {
    // All discovery state is touched on this (main) looper only: scan
    // callbacks post here before acting. @Volatile because connectBt02 /
    // disconnect can be called from a binder thread.
    private val scanHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var discoveryCallback: ScanCallback? = null

    @Volatile
    private var targetBleName: String? = null

    @Volatile
    private var discoveryStopped: Boolean = true

    // Exact BT02 local name to re-resolve the companion LE peripheral
    // by when its cached address stops answering (the BT02 uses a
    // random LE address). Null when we only know the address — then
    // the reader keeps retrying that address rather than guess at a
    // co-located operator's BT02.
    @Volatile
    private var rescanName: String? = null

    // Scan windows run since discovery (re)started. Drives the window
    // backoff, the scan mode and the filtered/unfiltered alternation.
    @Volatile
    private var discoveryWindows: Int = 0

    // Address-failure rounds handed to us by the base reader. Rescans
    // alternate with the base's own autoConnect attempts on the cached
    // address, so a BT02 that kept its address still comes back cheaply.
    @Volatile
    private var rescanRounds: Int = 0

    @Volatile
    private var scanPermissionReported: Boolean = false

    private val stopDiscoveryRunnable =
        Runnable {
            stopBleDiscovery(scheduleRetry = true)
        }

    private val retryDiscoveryRunnable =
        Runnable {
            if (discoveryStopped || isReaderDisposed()) return@Runnable
            startBleDiscovery()
        }

    /**
     * Connect to an Inrico BT02 regardless of whether [device] is:
     *   - the Classic BR/EDR audio device bonded in Android Bluetooth
     *     Settings (`B02PTT-xxxx`, `DEVICE_TYPE_CLASSIC`), or
     *   - the BLE GATT button peripheral directly (`DEVICE_TYPE_LE` /
     *     unbonded random LE address).
     *
     * When given the bonded Classic device, the BT02 exposes its button
     * GATT server on a separate random LE address advertising the same
     * local name (`B02PTT-xxxx`). In that case we run a background BLE
     * scan to resolve the matching LE peripheral automatically so the
     * operator only has to pair the BT02 once in Android Settings.
     */
    fun connectBt02(device: BluetoothDevice) {
        if (isReaderDisposed()) {
            Log.w(TAG, "connectBt02() called on disposed reader — ignoring")
            return
        }
        val devType =
            try {
                device.type
            } catch (_: Throwable) {
                BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
        val devName =
            try {
                device.name?.trim()
            } catch (_: Throwable) {
                null
            }
        if (devType == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            disconnect()
            scanPermissionReported = false
            rescanRounds = 0
            targetBleName = devName?.takeIf { it.isNotBlank() }
            rescanName = targetBleName?.takeIf { isBt02Name(it) }
            Log.i(
                TAG,
                "connectBt02: Classic device ${redactMac(device.address)} (name=$targetBleName) " +
                    "— scanning BLE for companion button peripheral",
            )
            beginDiscovery()
        } else {
            connect(device)
            // A saved LE address: remember the name (if this is a BT02)
            // so a rotated address can be re-resolved later.
            rescanName = devName?.takeIf { isBt02Name(it) }
            rescanRounds = 0
        }
    }

    /**
     * The cached LE address keeps failing: the BT02 has most likely
     * rotated its random address. Re-resolve by name — alternating with
     * the base reader's autoConnect retries on the old address — instead
     * of retrying a dead address forever. A bond (security) failure is
     * not an address problem: leave it to the base's slow auth backoff.
     */
    override fun onReconnectNeeded(
        reason: String,
        failedAttempts: Int,
        authFailure: Boolean,
    ): Boolean {
        val name = rescanName
        // Rounds only count once the address has failed enough times, so
        // the scan / address-retry alternation starts on a scan.
        val eligible = name != null && !authFailure && failedAttempts >= RESCAN_AFTER_FAILURES
        val rescan = eligible && shouldRescanByName(failedAttempts, authFailure, rescanRounds++)
        if (rescan) {
            Log.i(TAG, "companion LE address unresponsive after $failedAttempts attempts ($reason) — re-scanning for $name")
            targetBleName = name
            beginDiscovery()
        }
        return rescan
    }

    // With a name to re-resolve by, stay on direct connects (which time
    // out and come back to [onReconnectNeeded]) instead of autoConnect.
    override fun allowAutoConnect(): Boolean = rescanName == null

    override fun disconnect() {
        discoveryStopped = true
        targetBleName = null
        rescanName = null
        scanHandler.removeCallbacks(stopDiscoveryRunnable)
        scanHandler.removeCallbacks(retryDiscoveryRunnable)
        stopScanCallbackOnly()
        super.disconnect()
    }

    private fun beginDiscovery() {
        discoveryStopped = false
        discoveryWindows = 0
        scanHandler.removeCallbacks(retryDiscoveryRunnable)
        scanHandler.post(retryDiscoveryRunnable)
    }

    private fun isScreenOn(): Boolean =
        try {
            (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }

    private fun startBleDiscovery() {
        if (discoveryStopped || isReaderDisposed()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
        if (scanner == null) {
            // Adapter off: the plugin's STATE_ON reconnect pass restarts
            // the reader, so just stop here.
            Log.w(TAG, "BLE scanner unavailable (adapter off?) — pausing BT02 discovery")
            return
        }
        stopScanCallbackOnly()
        val expectedName = targetBleName
        val window = discoveryWindows++
        val screenOn = isScreenOn()
        val cb = companionScanCallback(expectedName)
        discoveryCallback = cb
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(scanModeFor(window))
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
        val filters = scanFiltersFor(expectedName, window, screenOn)
        Log.i(
            TAG,
            "BT02 discovery window $window: ${if (filters == null) "unfiltered" else "name-filtered"} " +
                "mode=${scanModeFor(window)} screenOn=$screenOn",
        )
        try {
            scanner.startScan(filters, settings, cb)
            scanHandler.removeCallbacks(stopDiscoveryRunnable)
            scanHandler.postDelayed(stopDiscoveryRunnable, DISCOVERY_WINDOW_MS)
        } catch (se: SecurityException) {
            // BLUETOOTH_SCAN missing: retrying cannot help until the
            // operator grants it. Say so once and stop; the next connect
            // (picker, STATE_ON pass) tries again.
            Log.e(TAG, "startScan refused — BLUETOOTH_SCAN not granted; BT02 discovery stopped", se)
            discoveryCallback = null
            discoveryStopped = true
            if (!scanPermissionReported) {
                scanPermissionReported = true
                reportProblem("Inrico BT02 can't be found: grant XV the Nearby devices permission")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startScan threw while locating BT02", t)
            discoveryCallback = null
            scheduleNextWindow()
        }
    }

    private fun companionScanCallback(expectedName: String?): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                val dev = result?.device ?: return
                val advName =
                    result.scanRecord?.deviceName?.trim()
                        ?: try {
                            dev.name?.trim()
                        } catch (_: Throwable) {
                            null
                        }
                if (!matchesCompanionBleName(expectedName, advName)) return
                scanHandler.post {
                    if (discoveryStopped || isReaderDisposed()) return@post
                    Log.i(
                        TAG,
                        "resolved companion BLE peripheral ${redactMac(dev.address)} " +
                            "name=$advName rssi=${result.rssi}",
                    )
                    discoveryStopped = true
                    scanHandler.removeCallbacks(stopDiscoveryRunnable)
                    scanHandler.removeCallbacks(retryDiscoveryRunnable)
                    stopScanCallbackOnly()
                    if (rescanName == null) rescanName = expectedName?.takeIf { isBt02Name(it) }
                    // Keeps failure history and notice flags (unlike
                    // connect()), so a bond problem is not re-announced on
                    // every address re-resolution.
                    reconnectTo(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE companion scan failed (code=$errorCode)")
                scanHandler.post {
                    stopBleDiscovery(scheduleRetry = true)
                }
            }
        }

    private fun stopBleDiscovery(scheduleRetry: Boolean) {
        scanHandler.removeCallbacks(stopDiscoveryRunnable)
        stopScanCallbackOnly()
        if (!scheduleRetry || discoveryStopped || isReaderDisposed()) return
        if (hasConnectTarget()) {
            // A re-resolution scan missed: go back to the base reader's
            // (backed-off) retries on the cached address. Its next failure
            // round brings us back here, so scans and address retries
            // alternate instead of scanning continuously.
            discoveryStopped = true
            Log.i(TAG, "re-resolution scan window missed — resuming retries on the cached address")
            resumeTargetRetries()
        } else {
            scheduleNextWindow()
        }
    }

    private fun scheduleNextWindow() {
        val delay = discoveryRetryDelayMs(discoveryWindows)
        Log.i(TAG, "BT02 BLE peripheral not found yet — next scan window in ${delay}ms")
        scanHandler.removeCallbacks(retryDiscoveryRunnable)
        scanHandler.postDelayed(retryDiscoveryRunnable, delay)
    }

    private fun stopScanCallbackOnly() {
        val cb = discoveryCallback ?: return
        discoveryCallback = null
        try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "XvBt02Ble"

        // Consecutive failed attempts on a cached LE address before
        // re-resolving the BT02 by name.
        const val RESCAN_AFTER_FAILURES: Int = 3

        /**
         * Whether this address-failure round should re-resolve by name.
         * Never for a bond failure; otherwise after [RESCAN_AFTER_FAILURES]
         * failures, on alternate rounds (even [round]) so the base's
         * autoConnect retries on the cached address interleave.
         */
        fun shouldRescanByName(
            failedAttempts: Int,
            authFailure: Boolean = false,
            round: Int = 0,
        ): Boolean = !authFailure && failedAttempts >= RESCAN_AFTER_FAILURES && round % 2 == 0

        /**
         * Scan filters for discovery window [window]. When
         * [expectedName] is a BT02 name, an exact-name filter is used on
         * even windows and on every window while the screen is off
         * (Android delivers no unfiltered results then); odd windows with
         * the screen on run unfiltered as a fallback for a name whose case
         * differs. Without a BT02 name every window is unfiltered.
         * Returns null for an unfiltered scan.
         */
        fun scanFiltersFor(
            expectedName: String?,
            window: Int,
            screenOn: Boolean = true,
        ): List<ScanFilter>? {
            val name = expectedName?.trim()?.takeIf { isBt02Name(it) }
            return if (name != null && useNameFilter(name, window, screenOn)) {
                listOf(ScanFilter.Builder().setDeviceName(name).build())
            } else {
                null
            }
        }

        /** Pure half of [scanFiltersFor]. */
        fun useNameFilter(
            expectedName: String?,
            window: Int,
            screenOn: Boolean = true,
        ): Boolean = isBt02Name(expectedName) && (!screenOn || window % 2 == 0)

        /** Low latency for the first windows, then balanced to save power. */
        fun scanModeFor(window: Int): Int =
            if (window < LOW_LATENCY_WINDOWS) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED

        /** Gap before the next window: 15 s doubling to a 5 min cap. */
        fun discoveryRetryDelayMs(windowsSoFar: Int): Long =
            (DISCOVERY_RETRY_MS shl (windowsSoFar - 1).coerceIn(0, 5)).coerceAtMost(MAX_DISCOVERY_RETRY_MS)

        private const val LOW_LATENCY_WINDOWS = 2
        private const val MAX_DISCOVERY_RETRY_MS = 300_000L
        private const val DISCOVERY_WINDOW_MS = 10_000L
        private const val DISCOVERY_RETRY_MS = 15_000L

        /**
         * Returns true when an advertised BLE local name [advertisedName]
         * matches the companion BT02 peripheral for a Classic BT02 named
         * [expectedClassicName]. Prefers exact name match (e.g. both
         * `B02PTT-0000`) when [expectedClassicName] is a BT02 name; falls
         * back to prefix matching when [expectedClassicName] is null/blank.
         */
        fun matchesCompanionBleName(
            expectedClassicName: String?,
            advertisedName: String?,
        ): Boolean {
            val adv = advertisedName?.trim().orEmpty()
            if (!isBt02Name(adv)) return false
            val exp = expectedClassicName?.trim().orEmpty()
            return exp.isEmpty() || !isBt02Name(exp) || adv.equals(exp, ignoreCase = true)
        }

        /**
         * Notify characteristic that carries the BT02 button code.
         * Captured from the on-device probe; stable across the units
         * on hand. If a future firmware revision changes it, add the
         * new UUID to the config's `candidateCharacteristicUuids` list.
         */
        val BT02_BUTTON_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("894c8042-e841-461c-a5c9-5a73d25db08e")

        /**
         * Decode the BT02's single button-code byte to the set of
         * currently-held buttons. The byte is an enumerated code, NOT
         * a bitmask, so this is an exact-match `when`, not a series of
         * bit tests — e.g. `0x09` is not `PTT | emergency`, it is an
         * unknown code and decodes to nothing held.
         *
         * Kept as a top-level function so unit tests can pin the exact
         * mapping without touching the Android GATT stack, mirroring
         * [PrymeBleReader.decodePrymeMask] /
         * [ZelloBleReader.decodeZelloMask].
         */
        fun decodeBt02Code(code: Int): Set<AinaButton> =
            when (code) {
                0x01 -> setOf(AinaButton.PTT)
                // 0x00 released; 0x03 top button intentionally unmapped;
                // 0x08 emergency is a one-shot pulse (no 0x00 release) and
                // is handled by decodeBt02Pulse; any other value holds nothing.
                else -> emptySet()
            }

        /**
         * Decode one-shot pulse codes emitted by the BT02 without a
         * subsequent `0x00` release frame. The SOS button holds internally
         * for ~2 seconds and emits a single `0x08` notification; firing a
         * momentary down+up pulse triggers [EmergencyController] to fire
         * panic instead of timing out into its 1s long-hold cancel path.
         */
        fun decodeBt02Pulse(code: Int): Set<AinaButton> =
            when (code) {
                0x08 -> setOf(AinaButton.PTTE)
                else -> emptySet()
            }

        /**
         * Advertised Complete Local Name prefix the BT02 broadcasts.
         * The full name is `B02PTT-<unit>`, where `<unit>` is a
         * per-device suffix that tracks the hardware MAC — so we match
         * on the PREFIX only. That recognizes every BT02 unit and keeps
         * any one operator's device-specific suffix out of the code.
         */
        const val BT02_NAME_PREFIX: String = "B02PTT"

        /**
         * True iff [name] is a BT02's advertised local name. Used by
         * [BlePttScanner] to surface the device during a scan and by
         * the External Button kind resolver to route it to this reader
         * instead of the HM-10 / Pryme reader. Null / blank / any other
         * name → false. Case-insensitive, leading/trailing space
         * tolerant.
         */
        fun isBt02Name(name: String?): Boolean =
            name?.trim()?.startsWith(BT02_NAME_PREFIX, ignoreCase = true) == true

        private val BT02_CONFIG =
            Config(
                tag = "XvBt02Ble",
                candidateServiceUuids = emptyList(),
                decodeMask = ::decodeBt02Code,
                candidateCharacteristicUuids = listOf(BT02_BUTTON_CHARACTERISTIC_UUID),
                decodePulse = ::decodeBt02Pulse,
            )
    }
}
