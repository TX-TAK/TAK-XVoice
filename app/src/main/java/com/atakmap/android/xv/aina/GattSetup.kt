package com.atakmap.android.xv.aina

/**
 * Pure (Android-free) pieces of BLE PTT reader setup, shared by
 * [AinaBleReader] and [BitmaskGattPttReader] and unit-tested directly.
 *
 * The readers' contract is that "connected" means "button notifications
 * are subscribed": a reader reports connected only after every CCCD
 * write it issued has been confirmed by onDescriptorWrite, and any
 * failure between the ACL connect and that point (discovery failure,
 * missing service / characteristic / CCCD, a rejected or failed
 * descriptor write, the setup watchdog) tears the link down and goes
 * back through the reconnect backoff instead of being logged and left
 * half-configured.
 */
object GattSetup {
    // GATT *operation* status codes (onServicesDiscovered,
    // onDescriptorWrite) meaning the link-layer security the peripheral
    // demands is not in place — in practice a stale or one-sided bond.
    const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    const val GATT_INSUFFICIENT_ENCRYPTION = 15
    const val GATT_AUTH_FAIL = 137

    // HCI *disconnect reasons* (the status of onConnectionStateChange)
    // that mean the same thing. A different number space from the
    // operation statuses above: HCI 15 (0x0F) is "unacceptable BD_ADDR",
    // not an encryption problem.
    const val HCI_AUTHENTICATION_FAILURE = 5
    const val HCI_PIN_OR_KEY_MISSING = 6
    const val HCI_MIC_FAILURE = 61

    fun isAuthFailure(status: Int?): Boolean = isGattOpAuthFailure(status)

    /** For the status of a GATT operation callback. */
    fun isGattOpAuthFailure(status: Int?): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_AUTH_FAIL

    /** For the HCI reason delivered with STATE_DISCONNECTED. */
    fun isHciStaleBond(reason: Int?): Boolean =
        reason == HCI_AUTHENTICATION_FAILURE ||
            reason == HCI_PIN_OR_KEY_MISSING ||
            reason == HCI_MIC_FAILURE

    // Minimum gap between BluetoothGatt.close() and the next
    // connectGatt(). The BLE stack needs ~100 ms to release the prior
    // handle; reopening sooner breeds status=133 wedge cycles. 120 ms
    // covers slower OEM stacks.
    const val CLOSE_REOPEN_SETTLE_MS: Long = 120L

    /** Milliseconds still to wait before reopening, or 0 to go now. */
    fun reopenDelayMs(
        lastCloseMs: Long,
        nowMs: Long,
    ): Long {
        if (lastCloseMs <= 0L) return 0L
        val since = nowMs - lastCloseMs
        return if (since in 0 until CLOSE_REOPEN_SETTLE_MS) CLOSE_REOPEN_SETTLE_MS - since else 0L
    }

    // From STATE_CONNECTED to a confirmed subscription. A peripheral that
    // links up and then stalls (discovery or CCCD write never answered)
    // is torn down and retried rather than left half-configured.
    const val SETUP_WATCHDOG_MS: Long = 15_000L

    // Consecutive "no button characteristic / service" setups before the
    // reader stops treating it as transient.
    const val UNRECOGNISED_LIMIT: Int = 3

    const val MAX_DIRECT_RETRIES: Int = 4
    const val INITIAL_RETRY_DELAY_MS: Long = 2_000L
    const val MAX_DIRECT_RETRY_DELAY_MS: Long = 16_000L
    const val AUTO_CONNECT_DELAY_MS: Long = 5_000L

    // Direct-connect cadence once the budget is spent, for readers that
    // must not park in autoConnect (it waits forever on a stale address).
    const val SLOW_DIRECT_RETRY_DELAY_MS: Long = 60_000L

    // A broken bond cannot heal on its own; retry rarely until re-paired.
    const val AUTH_RETRY_DELAY_MS: Long = 120_000L

    // A device that never exposes the button characteristic is either the
    // wrong device or wrong firmware; check back occasionally.
    const val UNRECOGNISED_RETRY_DELAY_MS: Long = 300_000L

    /**
     * Delay before the next connect attempt. [directRetryCount] is the
     * number of failed attempts so far; [autoConnectArmed] means the
     * direct-retry budget is spent. [autoConnectWaits] says whether an
     * armed reader's autoConnect would actually wait on the radio: false
     * when the reader may not park in autoConnect at all, or when this
     * failure did not wait on the radio (see [autoConnectWaits]) — the
     * short autoConnect cadence would spin then, so the slow one is used.
     */
    fun retryDelayMs(
        directRetryCount: Int,
        autoConnectArmed: Boolean,
        authFailure: Boolean,
        unrecognisedCount: Int,
        autoConnectWaits: Boolean = true,
    ): Long =
        when {
            unrecognisedCount >= UNRECOGNISED_LIMIT -> UNRECOGNISED_RETRY_DELAY_MS
            authFailure -> AUTH_RETRY_DELAY_MS
            autoConnectArmed && !autoConnectWaits -> SLOW_DIRECT_RETRY_DELAY_MS
            autoConnectArmed -> AUTO_CONNECT_DELAY_MS
            else -> (INITIAL_RETRY_DELAY_MS shl directRetryCount.coerceIn(0, 4)).coerceAtMost(MAX_DIRECT_RETRY_DELAY_MS)
        }

    /**
     * Whether a re-armed autoConnect would wait for the device rather
     * than fail again at once. [failedFast]: connectGatt gave no handle,
     * or the link came up and setup failed (device in range).
     */
    fun autoConnectWaits(
        autoConnectAllowed: Boolean,
        failedFast: Boolean,
    ): Boolean = autoConnectAllowed && !failedFast

    /** autoConnect takes over once the direct budget is spent or the bond is broken. */
    fun shouldArmAutoConnect(
        directRetryCount: Int,
        authFailure: Boolean,
    ): Boolean = directRetryCount >= MAX_DIRECT_RETRIES || authFailure

    @android.annotation.SuppressLint("MissingPermission")
    fun defaultAdapterEnabled(): Boolean =
        try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Throwable) {
            // Can't tell (permission, stack) — don't block retries on it.
            true
        }

    /** Operator-message throttle: true when [nowMs] is past the window since [lastAtMs]. */
    fun shouldSurface(
        lastAtMs: Long,
        nowMs: Long,
        windowMs: Long,
    ): Boolean = lastAtMs <= 0L || nowMs - lastAtMs >= windowMs
}

/**
 * Serializes CCCD subscription writes: Android GATT allows one
 * outstanding operation per connection, so firing several
 * writeDescriptor calls back to back silently drops all but the first.
 * The reader starts the sequence, issues the [Step.Write] it gets, and
 * feeds every confirmed descriptor back through [onWritten] until it
 * sees [Step.Done]. A failed or rejected write is the reader's to
 * escalate; the sequencer only tracks order.
 */
class CccdSubscriptionSequencer<T : Any>(
    targets: List<T>,
) {
    sealed class Step<out T> {
        data class Write<T>(
            val target: T,
        ) : Step<T>()

        /** Every write confirmed — the subscription is live. */
        object Done : Step<Nothing>()

        /** Nothing to subscribe to; the device cannot deliver buttons. */
        object NothingToSubscribe : Step<Nothing>()

        /** A confirmation for something that is not the in-flight write. */
        object Ignore : Step<Nothing>()
    }

    private val queue = ArrayDeque(targets)
    private val total = targets.size

    @Volatile
    private var inFlight: T? = null

    var confirmed: Int = 0
        private set

    fun start(): Step<T> = if (total == 0) Step.NothingToSubscribe else advance()

    fun onWritten(target: T): Step<T> {
        if (target != inFlight) return Step.Ignore
        confirmed++
        return advance()
    }

    private fun advance(): Step<T> {
        val next = queue.removeFirstOrNull()
        inFlight = next
        return if (next == null) Step.Done else Step.Write(next)
    }
}
