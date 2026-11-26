package com.atakmap.android.xv.aina

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

// AINA APTT V2 — BLE GATT button-mask characteristic.
// UUIDs from "AINA APTT Communication Protocol v18", section 4.
@SuppressLint("MissingPermission")
class AinaBleReader(
    private val context: Context,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
    // Fired at most once per connect() when the AINA rejects the link
    // for security reasons (stale / one-sided bond): the operator has to
    // forget and re-pair it. The reader keeps retrying slowly.
    private val onNeedsRepair: (String) -> Unit = {},
    // Test seam — abstracts BluetoothDevice.connectGatt so unit
    // tests can supply a fake BluetoothGatt + drive callbacks
    // synchronously. Production uses [DefaultGattConnector]. Public
    // (rather than internal) so unit tests in the same module can
    // pass a SAM lambda via this primary constructor — Kotlin's
    // overload-resolution rules reject the lambda when the SAM type
    // is internal. Production callers never reference this type.
    private val gattConnector: GattConnector = DefaultGattConnector,
    // Non-bond problems the operator has to act on (buttons not
    // recognised). Fired once per condition.
    private val onProblem: (String) -> Unit = {},
    // Test seam: whether the Bluetooth adapter is on. Reconnects pause
    // while it is off.
    private val adapterEnabled: () -> Boolean = { GattSetup.defaultAdapterEnabled() },
) {

    private val decoder = ButtonMaskDecoder()
    private val handler = Handler(Looper.getMainLooper())

    // Guards [gatt] and the retry counters. connectGatt + assignment run
    // under it so a callback can tell whether it belongs to the current
    // connection; close is conditional on the handle a failure was
    // observed on (see [closeGatt]).
    private val gattLock = Any()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var targetDevice: BluetoothDevice? = null

    // "Connected" has been reported for this link — set only once the
    // button CCCD write is confirmed. Button notifications are ignored
    // until then.
    @Volatile
    private var reportedConnected: Boolean = false

    @Volatile
    private var lastFailureWasAuth: Boolean = false

    @Volatile
    private var repairReported: Boolean = false

    @Volatile
    private var unrecognisedCount: Int = 0

    @Volatile
    private var unrecognisedReported: Boolean = false

    @Volatile
    private var cacheRefreshTried: Boolean = false

    @Volatile
    private var watchdogGatt: BluetoothGatt? = null

    @Volatile
    private var disposed: Boolean = false

    @Volatile
    private var intentionalDisconnect: Boolean = false

    // Read and written only under [gattLock].
    private var directRetryCount: Int = 0
    private var autoConnectArmed: Boolean = false

    // Test-only read access to the retry counter / auto-arm flag.
    internal fun directRetryCountForTest(): Int = synchronized(gattLock) { directRetryCount }

    internal fun autoConnectArmedForTest(): Boolean = synchronized(gattLock) { autoConnectArmed }

    // Set in onServicesDiscovered after we kick off the CCCD write.
    // Cleared in onDescriptorWrite after we chain into the CONFIG read.
    // Android GATT serializes ops — we can't fire two writes/reads at
    // once, so we sequence them via the callback.
    @Volatile
    private var pendingConfigReadModifyWrite: Boolean = false

    private val retryRunnable =
        Runnable {
            val device = targetDevice ?: return@Runnable
            if (intentionalDisconnect) return@Runnable
            if (!adapterEnabled()) {
                // Pointless while the adapter is off; the plugin's STATE_ON
                // reconnect pass restarts the reader.
                Log.i(TAG, "Bluetooth adapter off — pausing reconnects until it is back on")
                return@Runnable
            }
            try {
                connectInternal(device, useAutoConnect = synchronized(gattLock) { autoConnectArmed })
            } catch (t: Throwable) {
                // SecurityException (permission revoked) or a stack
                // RuntimeException on the main looper must not crash ATAK.
                Log.w(TAG, "reconnect attempt threw", t)
                scheduleReconnect("reconnect attempt threw ${t.javaClass.simpleName}", expected = null)
            }
        }

    private val setupWatchdog =
        Runnable {
            val g = watchdogGatt ?: return@Runnable
            if (isCurrent(g) && !reportedConnected) {
                escalate(g, "button subscription not confirmed within ${GattSetup.SETUP_WATCHDOG_MS}ms")
            }
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        intentionalDisconnect = false
        targetDevice = device
        synchronized(gattLock) {
            directRetryCount = 0
            autoConnectArmed = false
        }
        lastFailureWasAuth = false
        repairReported = false
        unrecognisedCount = 0
        unrecognisedReported = false
        cacheRefreshTried = false
        // Through the runnable so the settle gap, the adapter check and
        // the exception guard apply to the first attempt too.
        handler.removeCallbacks(retryRunnable)
        handler.post(retryRunnable)
    }

    private fun connectInternal(
        device: BluetoothDevice,
        useAutoConnect: Boolean,
    ) {
        // M15: enforce ~100 ms between close() and the next connectGatt.
        // BLE stack on most Android versions needs that gap to fully
        // release the prior GATT handle; without it, status=133 wedge
        // cycles pile up. Defer via the retry runnable rather than
        // busy-waiting on the main looper.
        val waitMs = GattSetup.reopenDelayMs(lastGattCloseMs, android.os.SystemClock.elapsedRealtime())
        if (waitMs > 0L) {
            Log.i(TAG, "tryConnect: deferring ${waitMs}ms since last close() (BLE stack settle)")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, waitMs)
            return
        }
        releaseHeld()
        reportedConnected = false
        pendingConfigReadModifyWrite = false
        Log.i(
            TAG,
            "Connecting to AINA at ${redactMac(device.address)} " +
                "(autoConnect=$useAutoConnect, attempt=${synchronized(gattLock) { directRetryCount } + 1})",
        )
        val opened =
            synchronized(gattLock) {
                val g =
                    try {
                        gattConnector.connect(context, device, useAutoConnect, callback)
                    } catch (se: SecurityException) {
                        Log.w(TAG, "connectGatt threw SecurityException — BLUETOOTH_CONNECT revoked?", se)
                        null
                    } catch (t: Throwable) {
                        Log.w(TAG, "connectGatt threw", t)
                        null
                    }
                gatt = g
                g != null
            }
        if (!opened) scheduleReconnect("connectGatt returned no handle", expected = null)
    }

    fun isConnecting(): Boolean = gatt != null

    fun dispose() {
        disposed = true
        disconnect()
    }

    fun disconnect() {
        intentionalDisconnect = true
        targetDevice = null
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(setupWatchdog)
        releaseHeld()
        closeGatt(expected = null)
        reportedConnected = false
        dispatchConnectionState(false)
    }

    // Up edges for every button the decoder has held, then a clean
    // decoder. Runs before a connection is abandoned: a press delivered
    // by a link that then failed (our own close() delivers no
    // STATE_DISCONNECTED) must not stay keyed.
    private fun releaseHeld() {
        for ((button, isDown) in decoder.process(0)) {
            if (!isDown) {
                Log.i(TAG, "releasing $button held on an abandoned link")
                dispatchEvent(button, false)
            }
        }
        decoder.reset()
    }

    private fun isCurrent(g: BluetoothGatt): Boolean = synchronized(gattLock) { g === gatt }

    /**
     * Close the current handle. With [expected] non-null, only if it is
     * still that handle: a failure observed on a binder thread must not
     * close a newer connection that replaced it. Returns false when
     * [expected] was already stale.
     */
    private fun closeGatt(expected: BluetoothGatt?): Boolean {
        val g =
            synchronized(gattLock) {
                if (expected != null && gatt !== expected) return false
                val old = gatt
                gatt = null
                old
            }
        pendingConfigReadModifyWrite = false
        watchdogGatt = null
        handler.removeCallbacks(setupWatchdog)
        if (g == null) return true
        try {
            g.disconnect()
        } catch (_: Throwable) {
        }
        try {
            g.close()
        } catch (_: Throwable) {
        }
        // Stamp the close time. The BLE stack needs ~100 ms between a
        // close() and the next connectGatt() call to fully release the
        // GATT handle; without that gap, status=133 wedge cycles pile
        // up and the connection oscillates instead of recovering.
        // [connectInternal] consults this and defers when needed. Audit M15.
        lastGattCloseMs = android.os.SystemClock.elapsedRealtime()
        return true
    }

    @Volatile
    private var lastGattCloseMs: Long = 0L

    private fun scheduleReconnect(
        reason: String,
        expected: BluetoothGatt?,
    ) {
        if (intentionalDisconnect || disposed) return
        val device = targetDevice ?: return
        // Always close the failed handle before another attempt — status=133
        // typically leaves it unusable. A stale handle's failure belongs to
        // whoever replaced it.
        if (!closeGatt(expected)) return
        val auth = lastFailureWasAuth
        val unrecognised = unrecognisedCount
        // After several failed direct attempts, switch to autoConnect=true so
        // the BLE stack handles long-term reconnect when the AINA returns.
        // A broken bond or an unrecognised device retries rarely.
        val (delayMs, armed) =
            synchronized(gattLock) {
                if (GattSetup.shouldArmAutoConnect(directRetryCount, auth)) autoConnectArmed = true
                val d = GattSetup.retryDelayMs(directRetryCount, autoConnectArmed, auth, unrecognised)
                directRetryCount++
                d to autoConnectArmed
            }
        Log.i(
            TAG,
            "Scheduling reconnect to ${redactMac(device.address)} in ${delayMs}ms " +
                "($reason, autoConnect=$armed)",
        )
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun dispatchConnectionState(state: Boolean) {
        if (disposed) return
        try {
            onConnectionState(state)
        } catch (t: Throwable) {
            Log.w(TAG, "onConnectionState($state) handler threw", t)
        }
    }

    /**
     * Setup / post-connect failure on [g]: release anything held, report
     * disconnected, tear the half-configured link down and go back through
     * the backoff — instead of leaving the operator with a "connected"
     * speaker-mic whose buttons do nothing. [opStatus] is a GATT operation
     * status (not an HCI disconnect reason).
     */
    private fun escalate(
        g: BluetoothGatt,
        reason: String,
        opStatus: Int? = null,
    ) {
        if (!isCurrent(g)) return
        Log.w(TAG, "setup failed: $reason — reconnecting")
        val auth = GattSetup.isGattOpAuthFailure(opStatus)
        lastFailureWasAuth = auth
        if (auth) reportNeedsRepair("status=$opStatus ${gattStatusName(opStatus ?: -1)}")
        pendingConfigReadModifyWrite = false
        releaseHeld()
        reportedConnected = false
        dispatchConnectionState(false)
        scheduleReconnect(reason, expected = g)
    }

    private fun reportNeedsRepair(detail: String) {
        if (repairReported || disposed) return
        repairReported = true
        Log.e(TAG, "AINA rejected the button link ($detail) — operator must forget and re-pair")
        try {
            onNeedsRepair(detail)
        } catch (t: Throwable) {
            Log.w(TAG, "onNeedsRepair handler threw", t)
        }
    }

    // The AINA button service / characteristic / CCCD is not there.
    private fun onUnrecognised(g: BluetoothGatt) {
        val n = ++unrecognisedCount
        if (!cacheRefreshTried) {
            // A stale GATT cache can hide the service; clear it once.
            cacheRefreshTried = true
            try {
                val ok = g.javaClass.getMethod("refresh").invoke(g)
                Log.i(TAG, "GATT cache refresh requested (result=$ok)")
            } catch (t: Throwable) {
                Log.w(TAG, "GATT cache refresh unavailable: ${t.javaClass.simpleName}")
            }
        }
        if (n >= GattSetup.UNRECOGNISED_LIMIT && !unrecognisedReported && !disposed) {
            unrecognisedReported = true
            Log.e(TAG, "AINA button service missing after $n connects — wrong device or protocol")
            try {
                onProblem("Speaker-mic buttons not recognised — check the device and its button protocol setting")
            } catch (t: Throwable) {
                Log.w(TAG, "onProblem handler threw", t)
            }
        }
        escalate(g, "AINA button service / characteristic / CCCD unavailable ($n in a row)")
    }

    private fun onSubscribed(g: BluetoothGatt) {
        synchronized(gattLock) {
            directRetryCount = 0
            autoConnectArmed = false
        }
        lastFailureWasAuth = false
        repairReported = false
        unrecognisedCount = 0
        unrecognisedReported = false
        cacheRefreshTried = false
        handler.removeCallbacks(setupWatchdog)
        watchdogGatt = null
        // High priority only once the link is useful.
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (e: SecurityException) {
            Log.w(TAG, "Lacking BLUETOOTH_CONNECT permission to request high priority", e)
        }
        reportedConnected = true
        dispatchConnectionState(true)
    }

    private fun dispatchEvent(button: AinaButton, isDown: Boolean) {
        if (disposed) return
        try {
            onEvent(button, isDown)
        } catch (t: Throwable) {
            Log.w(TAG, "onEvent handler threw", t)
        }
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (!isCurrent(g)) {
                    // Late callback for a handle already closed/replaced.
                    Log.i(TAG, "ignoring stale GATT callback (state=$newState status=$status)")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            g.close()
                        } catch (_: Throwable) {
                        }
                    }
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(
                            TAG,
                            "Connected (status=$status ${gattStatusName(status)}), discovering services",
                        )
                        // Not "connected" yet — that waits for the CCCD
                        // write confirmation (onSubscribed), bounded by
                        // the setup watchdog. The retry counter also resets
                        // there, so a device that links up but never
                        // subscribes still backs off.
                        watchdogGatt = g
                        handler.removeCallbacks(setupWatchdog)
                        handler.postDelayed(setupWatchdog, GattSetup.SETUP_WATCHDOG_MS)
                        val started =
                            try {
                                g.discoverServices()
                            } catch (t: Throwable) {
                                Log.w(TAG, "discoverServices threw", t)
                                false
                            }
                        if (!started) escalate(g, "discoverServices() refused")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(
                            TAG,
                            "Disconnected (status=$status ${gattStatusName(status)} " +
                                "pendingConfig=$pendingConfigReadModifyWrite)",
                        )
                        releaseHeld()
                        // Clear the pending flag — a disconnect in the
                        // middle of the CCCD-write -> CONFIG-read
                        // sequence has invalidated the half-configured
                        // state. The reconnect path will re-enter
                        // onServicesDiscovered cleanly.
                        if (pendingConfigReadModifyWrite) {
                            Log.i(
                                TAG,
                                "pendingConfigReadModifyWrite: true -> false (disconnect mid-sequence)",
                            )
                            pendingConfigReadModifyWrite = false
                        }
                        reportedConnected = false
                        dispatchConnectionState(false)
                        if (!intentionalDisconnect) {
                            // [status] is an HCI disconnect reason here.
                            lastFailureWasAuth = GattSetup.isHciStaleBond(status)
                            if (lastFailureWasAuth) {
                                reportNeedsRepair("disconnect reason=$status")
                            }
                            scheduleReconnect("status=$status ${gattStatusName(status)}", expected = g)
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (!isCurrent(g)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    escalate(g, "service discovery failed: $status ${gattStatusName(status)}", status)
                    return
                }
                val cccd = buttonCccd(g)
                if (cccd == null) {
                    onUnrecognised(g)
                    return
                }
                val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                Log.i(
                    TAG,
                    "Firing CCCD write to enable button notifications " +
                        "(pendingConfigReadModifyWrite: false -> true)",
                )
                pendingConfigReadModifyWrite = true
                val queued =
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, enableValue) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = enableValue
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "writeDescriptor threw", t)
                        false
                    }
                if (!queued) {
                    escalate(g, "CCCD writeDescriptor rejected by the stack")
                    return
                }
                // CONFIG read is deferred until onDescriptorWrite fires —
                // Android GATT serializes operations and only allows one
                // in flight at a time. Reading immediately after queuing
                // a descriptor write returns false. The deferred read
                // sets a pending flag here so the descriptor-write callback
                // knows to chain into the read-modify-write.
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (!isCurrent(g)) return
                if (descriptor.uuid != CCCD_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Quick-win M5: CCCD-write failure used to just
                    // log + clear the pending flag. But
                    // setCharacteristicNotification(true) was already
                    // applied above, so the device sees a half-
                    // configured subscription — notifications may or
                    // may not flow, and there's no retry. Treat as a
                    // full reconnect: tear the GATT down and let
                    // scheduleReconnect's backoff bring it up clean.
                    Log.e(
                        TAG,
                        "CCCD write FAILED status=$status ${gattStatusName(status)} — " +
                            "device left half-configured, forcing reconnect",
                    )
                    Log.i(
                        TAG,
                        "pendingConfigReadModifyWrite: true -> false (CCCD-write failure)",
                    )
                    escalate(g, "CCCD write failed status=$status", status)
                    return
                }
                if (!pendingConfigReadModifyWrite) return
                Log.i(
                    TAG,
                    "CCCD write OK — pendingConfigReadModifyWrite: true -> false, chaining to CONFIG read",
                )
                pendingConfigReadModifyWrite = false
                // Subscription is now active — button notifications will
                // flow from this point. This is the diagnostic anchor
                // requested by issue #88: the log line below confirms
                // button-subscription readiness post-reconnect. Any button
                // presses from here on will arrive as onCharacteristicChanged.
                Log.i(TAG, "button subscription ACTIVE — speakermic PTT ready")
                onSubscribed(g)
                val service = g.getService(SERVICE_UUID) ?: return
                val configCh = service.getCharacteristic(CONFIG_CHAR_UUID)
                if (configCh == null) {
                    Log.w(TAG, "CONFIG characteristic ($CONFIG_CHAR_UUID) not present — A2DP-disable skipped")
                    return
                }
                if (!g.readCharacteristic(configCh)) {
                    Log.w(TAG, "readCharacteristic($CONFIG_CHAR_UUID) returned false — A2DP-disable skipped")
                } else {
                    Log.i(TAG, "kicking off CONFIG read to apply A2DP-controls-disable bit")
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (ch.uuid != CONFIG_CHAR_UUID || !isCurrent(g)) return
                onConfigRead(g, ch, status, ch.value)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (ch.uuid != CONFIG_CHAR_UUID || !isCurrent(g)) return
                onConfigRead(g, ch, status, value)
            }

            private fun onConfigRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
                rawValue: ByteArray?,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CONFIG read failed: status=$status ${gattStatusName(status)}")
                    return
                }
                if (rawValue == null || rawValue.isEmpty()) {
                    Log.w(TAG, "CONFIG read returned empty value")
                    return
                }
                val current = rawValue[0].toInt() and 0xFF
                // Set bit 5 (A2DP CONTROLS DISABLE per spec v18 section
                // 4.3). The protocol prose says this disables the
                // AINA's media-button intercepts (play/pause/next/prev),
                // not the A2DP profile itself — but empirical testing
                // on this firmware revision is the only way to know for
                // sure, and the bit is read/write/notify so we can
                // observe what the firmware actually does in response.
                val desired = current or CONFIG_BIT_A2DP_CONTROLS_DISABLE
                if (current == desired) {
                    Log.i(
                        TAG,
                        "CONFIG already has A2DP-controls-disable set (0x${"%02x".format(current)}) — nothing to write",
                    )
                    return
                }
                Log.i(
                    TAG,
                    "CONFIG read=0x${"%02x".format(current)} — writing 0x${"%02x".format(desired)} " +
                        "(setting A2DP_CONTROLS_DISABLE bit 5)",
                )
                val payload = byteArrayOf(desired.toByte())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = payload
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (ch.uuid != CONFIG_CHAR_UUID) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CONFIG write OK — A2DP-controls-disable bit applied")
                } else {
                    Log.w(TAG, "CONFIG write FAILED: status=$status ${gattStatusName(status)}")
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
            ) {
                if (disposed || !isCurrent(g)) return
                if (ch.uuid != BUTTON_CHAR_UUID) return
                handleMask(ch.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (disposed || !isCurrent(g)) return
                if (ch.uuid != BUTTON_CHAR_UUID) return
                handleMask(value)
            }

            private fun handleMask(bytes: ByteArray?) {
                if (bytes == null || bytes.isEmpty()) return
                if (!reportedConnected) {
                    // Subscription not confirmed: the link may still fail
                    // and be abandoned, so nothing it delivers counts yet.
                    Log.i(TAG, "ignoring button notify before subscription is confirmed")
                    return
                }
                val mask = bytes[0].toInt() and 0xFF
                for ((button, isDown) in decoder.process(mask)) {
                    dispatchEvent(button, isDown)
                }
            }
        }

    // Button CCCD with notifications enabled locally, or null when the
    // service, characteristic or descriptor is missing / refused.
    private fun buttonCccd(g: BluetoothGatt): BluetoothGattDescriptor? {
        val ch = g.getService(SERVICE_UUID)?.getCharacteristic(BUTTON_CHAR_UUID)
        if (ch == null) {
            Log.w(TAG, "AINA service or button characteristic not found on device")
            return null
        }
        if (!g.setCharacteristicNotification(ch, true)) {
            Log.w(TAG, "setCharacteristicNotification(true) failed")
            return null
        }
        return ch.getDescriptor(CCCD_UUID).also {
            if (it == null) Log.w(TAG, "CCCD missing on button characteristic")
        }
    }

    /**
     * Test seam — abstracts BluetoothDevice.connectGatt so unit
     * tests can supply a fake BluetoothGatt and drive callbacks
     * synchronously without standing up a real BLE stack. Public
     * so the unit tests in this module can pass a SAM lambda (Kotlin
     * rejects the lambda when the SAM type is internal). Production
     * callers don't reference this type at all.
     */
    fun interface GattConnector {
        fun connect(
            context: Context,
            device: BluetoothDevice,
            autoConnect: Boolean,
            callback: BluetoothGattCallback,
        ): BluetoothGatt?
    }

    object DefaultGattConnector : GattConnector {
        override fun connect(
            context: Context,
            device: BluetoothDevice,
            autoConnect: Boolean,
            callback: BluetoothGattCallback,
        ): BluetoothGatt? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, autoConnect, callback)
            }
    }

    companion object {
        private const val TAG = "AinaBleReader"

        // Retry cadence, the close→reopen settle gap and the setup
        // watchdog live in [GattSetup], shared with BitmaskGattPttReader.

        val SERVICE_UUID: UUID = UUID.fromString("127FACE1-CB21-11E5-93D0-0002A5D5C51B")
        val BUTTON_CHAR_UUID: UUID = UUID.fromString("127FBEEF-CB21-11E5-93D0-0002A5D5C51B")

        // CONFIG mask characteristic — spec v18 section 4.3. Read/write/notify
        // 8-bit unsigned integer; bit definitions live in CONFIG_BIT_* below.
        val CONFIG_CHAR_UUID: UUID = UUID.fromString("127FDEAF-CB21-11E5-93D0-0002A5D5C51B")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // CONFIG mask bit definitions (spec v18 section 4.3). The
        // dev-only bits (POWER, CLASSIC SPP STATE, CLASSIC RECONNECT,
        // COVERT) are intentionally NOT exposed here — flipping them
        // can brick the device per the spec's NOT-FOR-PRODUCTION note.
        const val CONFIG_BIT_PHONE_CONTROLS_DISABLE = 0x10
        const val CONFIG_BIT_A2DP_CONTROLS_DISABLE = 0x20

        // Decoded names for the common BluetoothGatt status codes we
        // see in field logs. Helps an operator skimming logcat tell
        // "133 = stack glitch, will retry" from "8 = AINA went to
        // sleep / out of range" without consulting a reference table.
        internal fun gattStatusName(status: Int): String =
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
                5 -> "AUTHENTICATION"
                8 -> "CONN_TIMEOUT"
                15 -> "INSUFFICIENT_ENCRYPTION"
                19 -> "GATT_CONN_TERMINATE_PEER_USER"
                22 -> "LMP_RESPONSE_TIMEOUT"
                133 -> "GATT_ERROR"
                137 -> "GATT_AUTH_FAIL"
                else -> "STATUS_$status"
            }
    }
}
