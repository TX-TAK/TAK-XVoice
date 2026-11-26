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
) {

    private val decoder = ButtonMaskDecoder()
    private val handler = Handler(Looper.getMainLooper())

    // Guards [gatt] so a callback can tell whether it belongs to the
    // current connection; connectGatt + assignment run under it.
    private val gattLock = Any()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var targetDevice: BluetoothDevice? = null

    // "Connected" has been reported for this link — set only once the
    // button CCCD write is confirmed.
    @Volatile
    private var reportedConnected: Boolean = false

    @Volatile
    private var lastFailureWasAuth: Boolean = false

    @Volatile
    private var repairReported: Boolean = false

    @Volatile
    private var disposed: Boolean = false

    @Volatile
    private var intentionalDisconnect: Boolean = false

    @Volatile
    private var directRetryCount: Int = 0

    @Volatile
    private var autoConnectArmed: Boolean = false

    // Test-only read access to the retry counter / auto-arm flag.
    // Package-internal because the M6 quick-win specifically needs
    // to verify that onServicesDiscovered success resets the
    // counter — there's no other clean way to observe this without
    // reaching for reflection or driving a multi-minute reconnect
    // schedule.
    internal fun directRetryCountForTest(): Int = directRetryCount

    internal fun autoConnectArmedForTest(): Boolean = autoConnectArmed

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
            try {
                connectInternal(device, useAutoConnect = autoConnectArmed)
            } catch (t: Throwable) {
                // SecurityException (permission revoked) or a stack
                // RuntimeException on the main looper must not crash ATAK.
                Log.w(TAG, "reconnect attempt threw", t)
                scheduleReconnect("reconnect attempt threw ${t.javaClass.simpleName}")
            }
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        intentionalDisconnect = false
        targetDevice = device
        directRetryCount = 0
        autoConnectArmed = false
        lastFailureWasAuth = false
        repairReported = false
        try {
            connectInternal(device, useAutoConnect = false)
        } catch (t: Throwable) {
            Log.w(TAG, "connect attempt threw", t)
            scheduleReconnect("connect attempt threw ${t.javaClass.simpleName}")
        }
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
        val sinceClose = android.os.SystemClock.elapsedRealtime() - lastGattCloseMs
        if (lastGattCloseMs > 0L && sinceClose < GATT_CLOSE_REOPEN_SETTLE_MS) {
            val waitMs = GATT_CLOSE_REOPEN_SETTLE_MS - sinceClose
            Log.i(
                TAG,
                "tryConnect: deferring ${waitMs}ms — only ${sinceClose}ms since last close() (BLE stack settle)",
            )
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, waitMs)
            return
        }
        decoder.reset()
        reportedConnected = false
        pendingConfigReadModifyWrite = false
        Log.i(
            TAG,
            "Connecting to AINA at ${redactMac(device.address)} " +
                "(autoConnect=$useAutoConnect, attempt=${directRetryCount + 1})",
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
        if (!opened) scheduleReconnect("connectGatt returned no handle")
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
        closeGatt()
        reportedConnected = false
        dispatchConnectionState(false)
    }

    private fun isCurrent(g: BluetoothGatt): Boolean = synchronized(gattLock) { g === gatt }

    private fun closeGatt() {
        val g =
            synchronized(gattLock) {
                val old = gatt
                gatt = null
                old
            }
        pendingConfigReadModifyWrite = false
        if (g == null) return
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
        // [tryConnect] consults this and defers when needed. Audit M15.
        lastGattCloseMs = android.os.SystemClock.elapsedRealtime()
    }

    @Volatile
    private var lastGattCloseMs: Long = 0L

    private fun scheduleReconnect(reason: String) {
        if (intentionalDisconnect) return
        val device = targetDevice ?: return

        // Always close the stale GATT handle before another attempt — status=133
        // typically leaves the handle unusable.
        closeGatt()

        // After several failed direct attempts, switch to autoConnect=true so
        // the BLE stack handles long-term reconnect when the AINA returns.
        // A security failure goes straight to the slow cadence: hammering
        // a device whose bond is broken achieves nothing.
        if (directRetryCount >= MAX_DIRECT_RETRIES || lastFailureWasAuth) {
            autoConnectArmed = true
        }

        val delayMs =
            if (autoConnectArmed) {
                AUTO_CONNECT_DELAY_MS
            } else {
                val base = INITIAL_RETRY_DELAY_MS shl directRetryCount.coerceAtMost(4)
                base.coerceAtMost(MAX_DIRECT_RETRY_DELAY_MS)
            }
        directRetryCount++

        Log.i(
            TAG,
            "Scheduling reconnect to ${redactMac(device.address)} in ${delayMs}ms " +
                "($reason, autoConnect=$autoConnectArmed)",
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
     * Setup / post-connect failure: tear the half-configured link down and
     * go back through the backoff, instead of logging and leaving the
     * operator with a "connected" speaker-mic whose buttons do nothing.
     */
    private fun escalate(
        reason: String,
        status: Int? = null,
    ) {
        Log.w(TAG, "setup failed: $reason — reconnecting")
        val auth = GattSetup.isAuthFailure(status)
        lastFailureWasAuth = auth
        if (auth) reportNeedsRepair("status=$status ${gattStatusName(status ?: -1)}")
        pendingConfigReadModifyWrite = false
        if (reportedConnected) {
            reportedConnected = false
            dispatchConnectionState(false)
        }
        scheduleReconnect(reason)
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

    private fun onSubscribed() {
        directRetryCount = 0
        autoConnectArmed = false
        lastFailureWasAuth = false
        repairReported = false
        reportedConnected = true
        dispatchConnectionState(true)
    }

    private fun dispatchEvent(button: AinaButton, isDown: Boolean) {
        if (disposed) return
        onEvent(button, isDown)
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
                        try {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Lacking BLUETOOTH_CONNECT permission to request high priority", e)
                        }
                        // Not "connected" yet — that waits for the CCCD
                        // write confirmation (onSubscribed). The retry
                        // counter also resets there, so a device that
                        // links up but never subscribes still backs off.
                        val started =
                            try {
                                g.discoverServices()
                            } catch (t: Throwable) {
                                Log.w(TAG, "discoverServices threw", t)
                                false
                            }
                        if (!started) escalate("discoverServices() refused")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(
                            TAG,
                            "Disconnected (status=$status ${gattStatusName(status)} " +
                                "pendingConfig=$pendingConfigReadModifyWrite)",
                        )
                        decoder.reset()
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
                            if (GattSetup.isAuthFailure(status)) {
                                lastFailureWasAuth = true
                                reportNeedsRepair("status=$status ${gattStatusName(status)}")
                            }
                            scheduleReconnect("status=$status ${gattStatusName(status)}")
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
                    escalate("service discovery failed: $status ${gattStatusName(status)}", status)
                    return
                }
                val cccd = buttonCccd(g)
                if (cccd == null) {
                    escalate("AINA button service / characteristic / CCCD unavailable")
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
                    escalate("CCCD writeDescriptor rejected by the stack")
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
                    escalate("CCCD write failed status=$status", status)
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
                onSubscribed()
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

        // Direct-connect retry strategy: a few quick attempts (handles
        // status=133 GATT_ERROR which is usually a transient stack glitch),
        // then fall back to autoConnect=true so the BLE stack itself handles
        // long-term reconnect when the AINA returns from sleep / out-of-range.
        private const val MAX_DIRECT_RETRIES = 4
        private const val INITIAL_RETRY_DELAY_MS = 2_000L

        // Minimum gap between BluetoothGatt.close() and the next
        // connectGatt(). The BLE stack needs ~100ms to fully release
        // the prior handle; without the wait, status=133 wedge cycles
        // accumulate instead of resolving. Conservative 120ms covers
        // OEM stacks (Samsung in particular is slower than stock
        // Android). Audit M15.
        private const val GATT_CLOSE_REOPEN_SETTLE_MS: Long = 120L
        private const val MAX_DIRECT_RETRY_DELAY_MS = 16_000L
        private const val AUTO_CONNECT_DELAY_MS = 5_000L

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
                5 -> "INSUFFICIENT_AUTHENTICATION"
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
