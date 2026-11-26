@file:android.annotation.SuppressLint("WrongConstant")

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
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Reusable BLE GATT plumbing for PTT buttons that expose their state
 * as a bitmask on one or more notify characteristics inside a vendor
 * service. Handles:
 *
 *   - `connectGatt` with a bounded direct-retry burst then a long-lived
 *     `autoConnect` for out-of-range recovery.
 *   - Service discovery, matching against a list of candidate service
 *     UUIDs (so a single reader class covers hardware revisions that
 *     differ only in advertised UUID).
 *   - CCCD subscription on the button characteristic(s), written one at
 *     a time (Android GATT allows a single outstanding operation). Where
 *     the vendor's button characteristic is known
 *     ([Config.buttonCharacteristicUuids]) only it is subscribed;
 *     otherwise every notify characteristic in the matched service.
 *   - "Connected" is reported only once every CCCD write is confirmed.
 *     Any failure before that (discovery, missing characteristic or
 *     CCCD, a rejected or failed write) drops the link and reconnects.
 *   - Edge-triggered PER-BUTTON down/up events derived from bitmask
 *     transitions between successive notifications, so a device that
 *     only reports "current mask" (no separate down/up packets) works
 *     transparently.
 *
 * Instances are configured by [Config] — each supported button family
 * (Pryme BT-PTT-Z, AINA V2, Zello-convention BLE, …) supplies its own
 * candidate service UUIDs, log tag, and mask-decode function. The
 * plumbing itself carries no vendor knowledge.
 *
 * Threading: all GATT callbacks arrive on the Bluetooth binder threads
 * and are handed off to a main-looper Handler for retry scheduling.
 * `onEvent` and `onConnectionState` are invoked from whichever thread
 * delivered the underlying GATT event — call sites that touch UI must
 * marshal to the UI thread themselves. This mirrors [AinaBleReader]'s
 * behavior so the existing `VoicePlant` wiring can bind either reader
 * interchangeably.
 */
@SuppressLint("MissingPermission")
open class BitmaskGattPttReader(
    private val context: Context,
    private val config: Config,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
    // Fired at most once per connect() when the device rejects the link
    // for security reasons (stale / one-sided bond). The operator has
    // to forget and re-pair; the reader keeps retrying slowly.
    private val onNeedsRepair: (String) -> Unit = {},
    // Test seam shared with [AinaBleReader].
    private val gattConnector: AinaBleReader.GattConnector = AinaBleReader.DefaultGattConnector,
    // Non-bond problems the operator has to act on (device not
    // recognised, BLUETOOTH_SCAN missing). Fired once per condition.
    private val onProblem: (String) -> Unit = {},
    // Test seam: whether the Bluetooth adapter is on. Reconnects pause
    // while it is off.
    private val adapterEnabled: () -> Boolean = { GattSetup.defaultAdapterEnabled() },
) {
    /**
     * Static per-vendor configuration passed to the reader at
     * construction. Everything vendor-specific lives here so the base
     * class stays generic.
     *
     * @param tag Log tag (grep-friendly per-vendor prefix, e.g.
     *   `XvPrymeBle` or `XvZelloBle`).
     * @param candidateServiceUuids Service UUIDs to try in priority
     *   order during service discovery. First match wins. Multiple
     *   entries let a single reader cover hardware revisions that
     *   changed UUIDs without changing wire format.
     * @param decodeMask Called on every incoming notification with the
     *   raw notification payload's first byte (masked to unsigned 8
     *   bits). Returns the set of buttons the vendor considers
     *   currently held. The base class diffs against the previous
     *   result to fire per-button down/up events.
     *
     *   A vendor that reports only "any button held / none held" (like
     *   the current Pryme heuristic) can return `setOf(PTT)` for any
     *   non-zero mask and `emptySet()` for zero.
     *
     *   A vendor that packs multiple buttons into one byte (like
     *   Zello's 0x01/0x02/0x04/0x08/0x10 bitmap) can decode each bit
     *   independently and return a multi-element set.
     */
    data class Config(
        val tag: String,
        val candidateServiceUuids: List<UUID>,
        val decodeMask: (mask: Int) -> Set<AinaButton>,
        /**
         * Optional notify characteristic UUID(s) to subscribe to,
         * matched across ALL discovered services regardless of which
         * service contains them. When non-empty this REPLACES the
         * [candidateServiceUuids]-based match: use it for hardware whose
         * button-state characteristic UUID is known but whose parent
         * vendor service UUID is unknown, unstable across firmware, or
         * simply not worth pinning (e.g. the Inrico BT02, which buries
         * its button characteristic under one of several vendor
         * services). Defaults to empty, so every existing
         * service-matched reader (Pryme / AINA / Zello) is byte-for-byte
         * unaffected.
         */
        val candidateCharacteristicUuids: List<UUID> = emptyList(),
        /**
         * Optional one-shot pulse decoder for hardware codes that emit a
         * single notification with no matching `0x00` release packet (e.g.
         * the Inrico BT02 SOS button, which waits ~2s internally and sends
         * a single `0x08` frame without a release). When non-empty for a
         * given byte, each returned button fires `down=true` followed
         * immediately by `down=false` without mutating [lastHeld].
         */
        val decodePulse: (mask: Int) -> Set<AinaButton> = { emptySet() },
        /**
         * Button characteristic(s) per matched service UUID. When the
         * matched service has an entry, only those characteristics are
         * subscribed — other notify characteristics in the same service
         * (UART echo, battery, status) can carry non-zero bytes that a
         * permissive mask decoder would read as a phantom PTT. A service
         * with no entry keeps the broad "every notify characteristic"
         * subscription. Ignored when [candidateCharacteristicUuids] is
         * set.
         */
        val buttonCharacteristicUuids: Map<UUID, List<UUID>> = emptyMap(),
    )

    private val handler = Handler(Looper.getMainLooper())

    // Guards [gatt] and the retry counters. connectGatt + assignment run
    // under it, so a callback racing the assignment waits for it instead
    // of being mistaken for a stale handle; close is conditional on the
    // handle a failure was observed on (see [abandonLink]).
    private val gattLock = Any()

    // Serializes "is this link live" against tearing it down. A
    // notification is decoded and dispatched only while its handle is
    // current and subscribed; a teardown detaches the handle, clears
    // [reportedConnected], releases held buttons and reports the drop
    // in one step. So a press can never be dispatched after the release
    // for its link, and "connected" can never be reported for a handle
    // that was already abandoned. Order: below VoicePlant's slot locks
    // (reader callbacks never take those), above [gattLock] and
    // PttDispatcher's lock. Handles are closed outside it.
    private val stateLock = Any()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var targetDevice: BluetoothDevice? = null

    @Volatile
    private var intentionalDisconnect: Boolean = false

    // Sticky "reader is being torn down for good" flag. Set by
    // [dispose] before the owner (VoicePlant) drops its reference.
    // Guards against late callbacks from the OS's binder queue — the
    // stale GATT callback closure can still be live for a few ms
    // after we've closed the gatt handle, and if VoicePlant has
    // already swapped in a fresh reader those late events would fire
    // into the plugin with a stale identity. Any callback dispatch
    // path (connection state, button events) short-circuits when
    // this is true.
    @Volatile
    private var disposed: Boolean = false

    // Retry counters: read and written only under [gattLock].
    private var directRetryCount: Int = 0
    private var autoConnectArmed: Boolean = false

    // CCCD writes for the current connection, issued one at a time.
    @Volatile
    private var subscription: CccdSubscriptionSequencer<BluetoothGattDescriptor>? = null

    // Characteristics this connection subscribed to. Notifications from
    // anything else are dropped before the mask decoder sees them.
    // Reset on every new connection and on close.
    @Volatile
    private var subscribedChars: Set<UUID> = emptySet()

    // True once onConnectionState(true) has been delivered for the
    // current connection — i.e. the button subscription is confirmed.
    // Button notifications are ignored until then.
    @Volatile
    private var reportedConnected: Boolean = false

    // A security/bond failure was seen since the last connect() or
    // confirmed subscription: retry rarely and don't nag the operator
    // more than once until it is fixed. Sticky — a later disconnect
    // with an unrelated reason (19, 133, 8: often the peripheral
    // dropping the link it refused to pair) must not collapse the slow
    // cadence back to the fast one.
    @Volatile
    private var lastFailureWasAuth: Boolean = false

    // The current attempt reached STATE_CONNECTED: the device is in
    // range, so a failure from here is a setup failure, not an address
    // or range problem.
    @Volatile
    private var attemptReachedLink: Boolean = false

    // connectGatt was refused for lack of BLUETOOTH_CONNECT. Retrying
    // cannot help until the operator grants it; reported once, retries
    // stop until the next connect().
    @Volatile
    private var permissionReported: Boolean = false

    @Volatile
    private var repairReported: Boolean = false

    // Consecutive setups that found no button characteristic.
    @Volatile
    private var unrecognisedCount: Int = 0

    @Volatile
    private var unrecognisedReported: Boolean = false

    @Volatile
    private var cacheRefreshTried: Boolean = false

    @Volatile
    private var lastGattCloseMs: Long = 0L

    // Handle the setup watchdog is guarding (set at STATE_CONNECTED).
    @Volatile
    private var watchdogGatt: BluetoothGatt? = null

    // Previous "buttons currently held" set, used to derive
    // edge-triggered down/up events. Starts empty (nothing held);
    // first notification with a non-empty decode = down events for
    // each button in the new set. Any path that abandons a connection
    // releases it first (see [releaseHeldLocked]) so a press can never
    // outlive the link that delivered it.
    @Volatile
    private var lastHeld: Set<AinaButton> = emptySet()

    private val retryRunnable =
        Runnable {
            val device = targetDevice ?: return@Runnable
            if (!wantsReconnect()) return@Runnable
            if (!adapterEnabled()) {
                // Pointless while the adapter is off; the STATE_ON
                // reconnect pass in the plugin restarts the reader.
                Log.i(config.tag, "Bluetooth adapter off — pausing reconnects until it is back on")
                return@Runnable
            }
            try {
                val armed = synchronized(gattLock) { autoConnectArmed }
                connectInternal(device, useAutoConnect = armed && allowAutoConnect())
            } catch (se: SecurityException) {
                onConnectPermissionDenied(se)
            } catch (t: Throwable) {
                // A stack RuntimeException on the main looper would
                // otherwise take ATAK down with it.
                Log.w(config.tag, "reconnect attempt threw", t)
                abandonLink(expected = null, notifyAlways = false)
                queueRetry("reconnect attempt threw ${t.javaClass.simpleName}", failedFast = true)
            }
        }

    private val setupWatchdog =
        Runnable {
            val g = watchdogGatt ?: return@Runnable
            if (isCurrent(g) && !reportedConnected) {
                escalate(g, "button subscription not confirmed within ${GattSetup.SETUP_WATCHDOG_MS}ms")
            }
        }

    /** Operator-initiated connect: resets every counter and notice flag. */
    fun connect(device: BluetoothDevice) {
        if (disposed) {
            Log.w(config.tag, "connect() called on disposed reader — ignoring")
            return
        }
        disconnect()
        synchronized(gattLock) {
            directRetryCount = 0
            autoConnectArmed = false
        }
        lastFailureWasAuth = false
        repairReported = false
        permissionReported = false
        unrecognisedCount = 0
        unrecognisedReported = false
        cacheRefreshTried = false
        startAttempts(device)
    }

    /**
     * Reader-initiated switch to [device] (e.g. the BT02's companion LE
     * peripheral re-resolved under a new random address). Unlike
     * [connect] it keeps the failure history and notice flags, so a
     * device that keeps rejecting the bond is not re-announced on every
     * internal reconnect.
     */
    protected fun reconnectTo(device: BluetoothDevice) {
        if (disposed) return
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(setupWatchdog)
        abandonLink(expected = null, notifyAlways = false)
        synchronized(gattLock) { directRetryCount = 0 }
        startAttempts(device)
    }

    private fun startAttempts(device: BluetoothDevice) {
        intentionalDisconnect = false
        targetDevice = device
        // dispose() may have run since the caller's check; the retry
        // runnable re-checks too, but don't even queue it.
        if (disposed) {
            intentionalDisconnect = true
            return
        }
        // Goes through the runnable so the close→reopen settle gap, the
        // adapter check and the exception guard apply to the first
        // attempt too.
        handler.removeCallbacks(retryRunnable)
        val wait = GattSetup.reopenDelayMs(lastGattCloseMs, SystemClock.elapsedRealtime())
        handler.postDelayed(retryRunnable, wait)
    }

    private fun connectInternal(
        device: BluetoothDevice,
        useAutoConnect: Boolean,
    ) {
        val wait = GattSetup.reopenDelayMs(lastGattCloseMs, SystemClock.elapsedRealtime())
        if (wait > 0L) {
            Log.i(config.tag, "deferring connect ${wait}ms — BLE stack settle after close()")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, wait)
            return
        }
        abandonLink(expected = null, notifyAlways = false)
        attemptReachedLink = false
        Log.i(
            config.tag,
            "Connecting to ${redactMac(device.address)} " +
                "(autoConnect=$useAutoConnect, attempt=${synchronized(gattLock) { directRetryCount } + 1})",
        )
        // The disposed check sits inside the lock so it is ordered
        // against dispose()'s close of the handle: either this attempt
        // sees disposed and opens nothing, or dispose closes what it opened.
        val outcome =
            synchronized(gattLock) {
                if (!wantsReconnect()) return
                try {
                    gatt = gattConnector.connect(context, device, useAutoConnect, callback)
                    if (gatt != null) ConnectOutcome.OPENED else ConnectOutcome.NO_HANDLE
                } catch (se: SecurityException) {
                    Log.w(config.tag, "connectGatt threw SecurityException — BLUETOOTH_CONNECT revoked?", se)
                    ConnectOutcome.NO_PERMISSION
                } catch (t: Throwable) {
                    Log.w(config.tag, "connectGatt threw", t)
                    ConnectOutcome.NO_HANDLE
                }
            }
        when (outcome) {
            ConnectOutcome.OPENED -> Unit
            ConnectOutcome.NO_PERMISSION -> onConnectPermissionDenied(null)
            ConnectOutcome.NO_HANDLE -> queueRetry("connectGatt returned no handle", failedFast = true)
        }
    }

    private enum class ConnectOutcome { OPENED, NO_HANDLE, NO_PERMISSION }

    // BLUETOOTH_CONNECT is missing: say so once and stop. The next
    // connect() (picker, STATE_ON pass, permission grant) tries again.
    private fun onConnectPermissionDenied(se: SecurityException?) {
        Log.e(config.tag, "connectGatt refused — BLUETOOTH_CONNECT not granted; reconnects stopped", se)
        handler.removeCallbacks(retryRunnable)
        if (!permissionReported) {
            permissionReported = true
            reportProblem("Bluetooth PTT button can't connect: grant XV the Nearby devices permission")
        }
    }

    open fun disconnect() {
        intentionalDisconnect = true
        targetDevice = null
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(setupWatchdog)
        abandonLink(expected = null, notifyAlways = true)
    }

    /** Button subscription confirmed and the reader not torn down. */
    fun isConnected(): Boolean = reportedConnected && !disposed

    protected fun isReaderDisposed(): Boolean = disposed

    /** True when the reader has a device it is (re)connecting to. */
    protected fun hasConnectTarget(): Boolean = targetDevice != null && !intentionalDisconnect

    /** Hand control back to the backed-off retry schedule for the current target. */
    protected fun resumeTargetRetries() {
        if (!hasConnectTarget() || disposed) return
        queueRetry("subclass resumed address retries", failedFast = false)
    }

    /** Consecutive failed attempts since the last confirmed subscription. */
    protected fun failedAttempts(): Int = synchronized(gattLock) { directRetryCount }

    /** Report a non-bond problem to the operator (wrong device, missing permission). */
    protected fun reportProblem(message: String) {
        if (disposed) return
        try {
            onProblem(message)
        } catch (t: Throwable) {
            Log.w(config.tag, "onProblem handler threw", t)
        }
    }

    /**
     * Permanent teardown. Marks the reader disposed so any late
     * callback still queued behind the OS's binder short-circuits
     * before it can fire back into the plugin with a stale identity.
     * Callers (VoicePlant on external-button swap / plugin shutdown)
     * should invoke this BEFORE nulling out their reference — after
     * dispose, [connect] is a no-op and every callback dispatch path
     * is muted. Idempotent.
     */
    fun dispose() {
        disposed = true
        disconnect()
    }

    /**
     * Whether long-term recovery may park in connectGatt(autoConnect =
     * true). A reader whose peripheral rotates its address must say no:
     * autoConnect waits forever on the old address and never fails, so
     * the reader would never get a chance to re-resolve it.
     */
    protected open fun allowAutoConnect(): Boolean = true

    /**
     * Hook for subclasses: called (on the main looper) just before a
     * reconnect is scheduled for an attempt that never reached the link
     * (an address / range failure). Not called when the device answered
     * and setup failed — the address is fine then, and that includes an
     * unrecognised device, which stays on its slow backoff. Return true
     * to take over — the base then schedules nothing.
     */
    protected open fun onReconnectNeeded(
        reason: String,
        failedAttempts: Int,
        authFailure: Boolean,
    ): Boolean = false

    /** Hook for subclasses: the button subscription was just confirmed. */
    protected open fun onSubscriptionConfirmed() = Unit

    /**
     * Single choke point for connection-state fan-out so [disposed]
     * gates every path — direct call from [disconnect] as well as
     * the GATT-callback path.
     */
    private fun dispatchConnectionState(up: Boolean) {
        if (disposed) return
        try {
            onConnectionState(up)
        } catch (t: Throwable) {
            Log.w(config.tag, "onConnectionState($up) handler threw", t)
        }
    }

    /**
     * Single choke point for button-event fan-out so [disposed]
     * gates late notifications too.
     */
    private fun dispatchEvent(
        button: AinaButton,
        isDown: Boolean,
    ) {
        if (disposed) return
        try {
            onEvent(button, isDown)
        } catch (t: Throwable) {
            Log.w(config.tag, "onEvent handler threw", t)
        }
    }

    // Send an up edge for every button the link currently has held. Runs
    // (under [stateLock]) whenever a connection is abandoned: a press
    // that arrived on a link which then failed (no STATE_DISCONNECTED
    // follows our own close()) must not stay keyed.
    private fun releaseHeldLocked() {
        val held = lastHeld
        lastHeld = emptySet()
        for (btn in held) {
            Log.i(config.tag, "releasing $btn held on an abandoned link")
            dispatchEvent(btn, false)
        }
    }

    private fun isCurrent(g: BluetoothGatt): Boolean = synchronized(gattLock) { g === gatt }

    /**
     * Abandon the current link. With [expected] non-null, only if it is
     * still the current handle: a failure observed on a binder thread
     * must not tear down a newer connection that replaced it. Under
     * [stateLock] the handle is detached first (so no callback for it
     * counts any more), then "connected" is cleared, held buttons are
     * released and — if it had been reported up, or [notifyAlways] —
     * the drop is reported. The handle is closed afterwards, outside
     * the lock. Returns false when [expected] was already stale.
     */
    private fun abandonLink(
        expected: BluetoothGatt?,
        notifyAlways: Boolean,
    ): Boolean {
        val old: BluetoothGatt?
        synchronized(stateLock) {
            old =
                synchronized(gattLock) {
                    if (expected != null && gatt !== expected) return false
                    gatt.also { gatt = null }
                }
            subscription = null
            subscribedChars = emptySet()
            watchdogGatt = null
            handler.removeCallbacks(setupWatchdog)
            val wasUp = reportedConnected
            reportedConnected = false
            releaseHeldLocked()
            if (wasUp || notifyAlways) dispatchConnectionState(false)
        }
        old?.let { closeHandle(it) }
        return true
    }

    private fun closeHandle(g: BluetoothGatt) {
        try {
            g.disconnect()
        } catch (_: Throwable) {
        }
        try {
            g.close()
        } catch (_: Throwable) {
        }
        lastGattCloseMs = SystemClock.elapsedRealtime()
    }

    /**
     * Setup or post-connect failure on [g]: release anything held, report
     * disconnected, drop the half-configured link and go back through the
     * reconnect backoff. [opStatus] is a GATT operation status; bond
     * failures among them are surfaced once as "needs re-pair" and
     * retried at the slow cadence.
     */
    private fun escalate(
        g: BluetoothGatt,
        reason: String,
        opStatus: Int? = null,
    ) {
        if (!abandonLink(expected = g, notifyAlways = true)) return
        Log.w(config.tag, "setup failed: $reason — reconnecting")
        if (GattSetup.isGattOpAuthFailure(opStatus)) {
            lastFailureWasAuth = true
            reportNeedsRepair("status=$opStatus")
        }
        // Every escalation follows STATE_CONNECTED: the device is in range.
        queueRetry(reason, failedFast = true, linkReached = true)
    }

    private fun reportNeedsRepair(detail: String) {
        if (repairReported || disposed) return
        repairReported = true
        Log.e(config.tag, "button link rejected by device security ($detail) — operator must re-pair")
        try {
            onNeedsRepair(detail)
        } catch (t: Throwable) {
            Log.w(config.tag, "onNeedsRepair handler threw", t)
        }
    }

    // Still supposed to be (re)connecting: not torn down by the owner.
    private fun wantsReconnect(): Boolean = !intentionalDisconnect && !disposed

    /**
     * Schedule the next attempt after a failure; the failed link has
     * already been abandoned. [failedFast]: the attempt failed without
     * waiting on the radio (see [GattSetup.retryDelayMs]). [linkReached]:
     * the device answered on this address, so the subclass hook (address
     * re-resolution) is not consulted — the address is fine.
     */
    private fun queueRetry(
        reason: String,
        failedFast: Boolean,
        linkReached: Boolean = false,
    ) {
        val device = targetDevice
        if (!wantsReconnect() || device == null) return
        val auth = lastFailureWasAuth
        val unrecognised = unrecognisedCount
        val (delayMs, armed, attempts) =
            synchronized(gattLock) {
                if (GattSetup.shouldArmAutoConnect(directRetryCount, auth)) autoConnectArmed = true
                val d =
                    GattSetup.retryDelayMs(
                        directRetryCount,
                        autoConnectArmed,
                        auth,
                        unrecognised,
                        allowAutoConnect(),
                        failedFast,
                    )
                directRetryCount++
                Triple(d, autoConnectArmed, directRetryCount)
            }
        handler.post {
            if (!wantsReconnect() || targetDevice !== device) return@post
            if (!linkReached && onReconnectNeeded(reason, attempts, auth)) return@post
            Log.i(
                config.tag,
                "Scheduling reconnect to ${redactMac(device.address)} in ${delayMs}ms " +
                    "($reason, autoConnect=$armed)",
            )
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delayMs)
        }
    }

    private fun onSubscribed(g: BluetoothGatt) {
        // Atomic with teardown: if the watchdog (or anything else)
        // abandoned [g] first, this confirmation is for a dead handle.
        synchronized(stateLock) {
            if (!isCurrent(g)) return
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
            reportedConnected = true
            Log.i(config.tag, "button subscription ACTIVE — PTT ready")
            dispatchConnectionState(true)
        }
        onSubscriptionConfirmed()
        // High priority only once the link is useful: a device stuck in a
        // setup-failure loop should not hold the radio at HIGH.
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (e: SecurityException) {
            Log.w(config.tag, "Lacking BLUETOOTH_CONNECT permission to request high priority", e)
        }
    }

    // No subscribable button characteristic on this device.
    private fun onUnrecognised(g: BluetoothGatt) {
        val n = ++unrecognisedCount
        if (!cacheRefreshTried) {
            // A stale GATT cache (firmware update, address reuse) can hide
            // the service; clear it once before concluding anything.
            cacheRefreshTried = true
            refreshGattCache(g)
        }
        if (n >= GattSetup.UNRECOGNISED_LIMIT && !unrecognisedReported) {
            unrecognisedReported = true
            Log.e(config.tag, "no button characteristic after $n connects — not a supported device/firmware")
            reportProblem("Bluetooth PTT device not recognised — check the device picked for this slot")
        }
        escalate(g, "no subscribable button characteristic ($n in a row)")
    }

    private fun refreshGattCache(g: BluetoothGatt) {
        try {
            val ok = g.javaClass.getMethod("refresh").invoke(g)
            Log.i(config.tag, "GATT cache refresh requested (result=$ok)")
        } catch (t: Throwable) {
            Log.w(config.tag, "GATT cache refresh unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun writeCccd(
        g: BluetoothGatt,
        cccd: BluetoothGattDescriptor,
    ): Boolean {
        val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enableValue) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (t: Throwable) {
            Log.w(config.tag, "writeDescriptor threw", t)
            false
        }
    }

    private fun runStep(
        g: BluetoothGatt,
        step: CccdSubscriptionSequencer.Step<BluetoothGattDescriptor>,
    ) {
        when (step) {
            is CccdSubscriptionSequencer.Step.Write -> {
                if (!writeCccd(g, step.target)) {
                    escalate(g, "writeDescriptor(enable) rejected for ${step.target.characteristic?.uuid}")
                }
            }
            CccdSubscriptionSequencer.Step.Done -> onSubscribed(g)
            CccdSubscriptionSequencer.Step.NothingToSubscribe -> onUnrecognised(g)
            CccdSubscriptionSequencer.Step.Ignore -> Unit
        }
    }

    // Turn on local notifications for a notify-capable [ch] and return
    // its CCCD to write, or null when it can't be subscribed.
    private fun enableLocally(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
    ): BluetoothGattDescriptor? {
        val notify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val cccd =
            when {
                !notify -> null
                !g.setCharacteristicNotification(ch, true) -> {
                    Log.w(config.tag, "setCharacteristicNotification(true) failed for ${ch.uuid}")
                    null
                }
                else ->
                    ch.getDescriptor(CCCD_UUID).also {
                        if (it == null) Log.w(config.tag, "CCCD missing on ${ch.uuid} — cannot enable notifications")
                    }
            }
        if (cccd != null) Log.i(config.tag, "subscribing to ${ch.uuid}")
        return cccd
    }

    // Matched characteristics for this device, restricted to the
    // configured button characteristic(s) where the vendor's is known.
    private fun selectTargetCharacteristics(g: BluetoothGatt): List<BluetoothGattCharacteristic> =
        if (config.candidateCharacteristicUuids.isNotEmpty()) {
            characteristicsByUuid(g)
        } else {
            characteristicsByService(g)
        }

    // Characteristic-first matching: locate the known button-state
    // characteristic(s) wherever they live in the GATT tree, without
    // needing to know (or trust) the parent vendor service UUID.
    private fun characteristicsByUuid(g: BluetoothGatt): List<BluetoothGattCharacteristic> {
        val found =
            g.services
                .asSequence()
                .flatMap { it.characteristics.asSequence() }
                .filter { it.uuid in config.candidateCharacteristicUuids }
                .toList()
        if (found.isEmpty()) {
            Log.w(
                config.tag,
                "no known characteristic on device " +
                    "(tried ${config.candidateCharacteristicUuids}) — wrong device or firmware",
            )
        }
        return found
    }

    private fun characteristicsByService(g: BluetoothGatt): List<BluetoothGattCharacteristic> {
        val service =
            config.candidateServiceUuids
                .asSequence()
                .mapNotNull { uuid -> g.getService(uuid) }
                .firstOrNull()
        if (service == null) {
            Log.w(
                config.tag,
                "no known service on device " +
                    "(tried ${config.candidateServiceUuids}) — bond may be wrong protocol",
            )
            return emptyList()
        }
        Log.i(config.tag, "matched service ${service.uuid}")
        val wanted =
            selectButtonCharacteristicUuids(
                serviceUuid = service.uuid,
                available = service.characteristics.map { it.uuid },
                buttonCharacteristicUuids = config.buttonCharacteristicUuids,
            )
        return service.characteristics.filter { it.uuid in wanted }
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (!isCurrent(g)) {
                    // Late callback for a handle we already closed or
                    // replaced. Never let it drive the current link.
                    Log.i(config.tag, "ignoring stale GATT callback (state=$newState status=$status)")
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
                        Log.i(config.tag, "Connected (status=$status), discovering services")
                        attemptReachedLink = true
                        // Not "connected" yet: that waits for the CCCD
                        // write confirmation. The watchdog bounds the wait.
                        watchdogGatt = g
                        handler.removeCallbacks(setupWatchdog)
                        handler.postDelayed(setupWatchdog, GattSetup.SETUP_WATCHDOG_MS)
                        val started =
                            try {
                                g.discoverServices()
                            } catch (t: Throwable) {
                                Log.w(config.tag, "discoverServices threw", t)
                                false
                            }
                        if (!started) escalate(g, "discoverServices() refused")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(config.tag, "Disconnected (status=$status)")
                        // Two paths can land here in the same
                        // millisecond during a controlled teardown:
                        //   1. [disconnect] already fired the false
                        //      callback synchronously.
                        //   2. The OS then delivers its async
                        //      STATE_DISCONNECTED echo via this
                        //      callback.
                        // Gate on [intentionalDisconnect] so the
                        // async echo is a no-op — it would otherwise
                        // fire a duplicate `up=false` into VoicePlant
                        // and log-spam the field capture (see the
                        // 2026-07-10 Pryme reconnect trace).
                        // Real disconnects (link drop, power-off,
                        // out-of-range) still fall through to both
                        // the callback AND queueRetry.
                        when (classifyDisconnect(intentionalDisconnect, disposed)) {
                            DisconnectAction.NOTIFY_AND_RECONNECT -> {
                                if (!abandonLink(expected = g, notifyAlways = true)) return
                                // [status] here is an HCI disconnect reason.
                                // Auth stays sticky (see lastFailureWasAuth).
                                if (GattSetup.isHciStaleBond(status)) {
                                    lastFailureWasAuth = true
                                    reportNeedsRepair("disconnect reason=$status")
                                }
                                val reached = attemptReachedLink
                                queueRetry("status=$status", failedFast = reached, linkReached = reached)
                            }
                            DisconnectAction.SUPPRESS_INTENTIONAL,
                            DisconnectAction.SUPPRESS_DISPOSED,
                            -> abandonLink(expected = g, notifyAlways = false)
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
                    escalate(g, "service discovery failed status=$status", status)
                    return
                }
                // One-time enumeration of every service + characteristic
                // so bring-up on new hardware has a paper trail. Cheap
                // and only fires once per (re)connect.
                for (svc in g.services) {
                    Log.i(config.tag, "service: ${svc.uuid}")
                    for (ch in svc.characteristics) {
                        Log.i(config.tag, "  char: ${ch.uuid} [${describeProps(ch.properties)}]")
                    }
                }
                val cccds = selectTargetCharacteristics(g).mapNotNull { ch -> enableLocally(g, ch) }
                subscribedChars = cccds.mapNotNull { it.characteristic?.uuid }.toSet()
                val seq = CccdSubscriptionSequencer(cccds)
                subscription = seq
                runStep(g, seq.start())
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                val seq = subscription
                if (seq == null || descriptor.uuid != CCCD_UUID || !isCurrent(g)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    runStep(g, seq.onWritten(descriptor))
                } else {
                    escalate(g, "CCCD write failed status=$status on ${descriptor.characteristic?.uuid}", status)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
            ) {
                handleNotification(g, ch, ch.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotification(g, ch, value)
            }

            // Under [stateLock] end to end: the current/subscribed check,
            // the decode, the [lastHeld] update and the dispatch are one
            // step against teardown, so a press decoded here can never be
            // dispatched after abandonLink released this link's buttons.
            private fun handleNotification(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                bytes: ByteArray?,
            ) {
                if (bytes == null) return
                synchronized(stateLock) {
                    if (isCurrent(g)) handleCurrentNotification(ch, bytes)
                }
            }

            private fun handleCurrentNotification(
                ch: BluetoothGattCharacteristic,
                bytes: ByteArray,
            ) {
                if (!reportedConnected) {
                    // Subscription not confirmed: the link may still fail
                    // and be abandoned, so nothing it delivers counts yet.
                    Log.i(config.tag, "ignoring notify on ${ch.uuid} before subscription is confirmed")
                    return
                }
                if (ch.uuid !in subscribedChars) {
                    Log.i(config.tag, "ignoring notify on unsubscribed ${ch.uuid}")
                    return
                }
                Log.i(config.tag, "notify ${ch.uuid}: ${bytes.toHexString()}")
                if (bytes.isEmpty()) return
                val mask = bytes[0].toInt() and 0xFF
                val pulseButtons = config.decodePulse(mask)
                if (pulseButtons.isNotEmpty()) {
                    for (btn in pulseButtons) {
                        dispatchEvent(btn, true)
                        dispatchEvent(btn, false)
                    }
                    return
                }
                val nowHeld = config.decodeMask(mask)
                val prev = lastHeld
                val edges = diffEdges(prev, nowHeld) ?: return
                lastHeld = nowHeld
                for (btn in edges.down) dispatchEvent(btn, true)
                for (btn in edges.up) dispatchEvent(btn, false)
            }
        }

    private fun describeProps(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString(",")
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Pure computation of down/up edges between two "held" sets.
     * Returns `null` when there is nothing to fire (identical sets),
     * so callers can early-return without allocating an [Edges].
     *
     * Extracted as a top-level companion function so unit tests can
     * pin the diff behavior without instantiating the reader (which
     * requires an Android [Context] and a live GATT stack).
     */
    data class Edges(
        val down: List<AinaButton>,
        val up: List<AinaButton>,
    )

    /**
     * What to do when the OS delivers `STATE_DISCONNECTED`.
     *
     * Split out from [onConnectionStateChange] as a pure decision
     * function so unit tests can pin the state table without an
     * Android GATT stack.
     */
    enum class DisconnectAction {
        /** Real link drop — fire the callback and schedule reconnect. */
        NOTIFY_AND_RECONNECT,

        /** Controlled teardown — [disconnect] already fired the callback synchronously; the OS's async echo is redundant. */
        SUPPRESS_INTENTIONAL,

        /** Reader has been [dispose]d — the owner has moved on, do not fire back. */
        SUPPRESS_DISPOSED,
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Which characteristic UUIDs of a matched service to subscribe
         * to. A service listed in [buttonCharacteristicUuids] is
         * restricted to its button characteristic(s) that are actually
         * present; any other service keeps every characteristic (the
         * caller still filters to notify-capable ones).
         */
        fun selectButtonCharacteristicUuids(
            serviceUuid: UUID,
            available: List<UUID>,
            buttonCharacteristicUuids: Map<UUID, List<UUID>>,
        ): List<UUID> {
            val known = buttonCharacteristicUuids[serviceUuid] ?: return available
            return available.filter { it in known }
        }

        fun diffEdges(
            prev: Set<AinaButton>,
            now: Set<AinaButton>,
        ): Edges? {
            if (prev == now) return null
            val down = now.filter { it !in prev }
            val up = prev.filter { it !in now }
            return Edges(down = down, up = up)
        }

        /**
         * Decision table for `STATE_DISCONNECTED` handling.
         * `disposed` beats `intentional` — a disposed reader should
         * never fire back into the plugin under any circumstances.
         */
        fun classifyDisconnect(
            intentional: Boolean,
            disposed: Boolean,
        ): DisconnectAction =
            when {
                disposed -> DisconnectAction.SUPPRESS_DISPOSED
                intentional -> DisconnectAction.SUPPRESS_INTENTIONAL
                else -> DisconnectAction.NOTIFY_AND_RECONNECT
            }
    }
}
