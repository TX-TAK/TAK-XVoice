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
 * descriptor write) tears the link down and goes back through the
 * reconnect backoff instead of being logged and left half-configured.
 */
object GattSetup {
    // BluetoothGatt status codes that mean the link-layer security the
    // peripheral demands is not in place — in practice a stale or
    // one-sided bond. Retrying the same bond cannot fix these; the
    // operator has to forget and re-pair the device.
    const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    const val GATT_INSUFFICIENT_ENCRYPTION = 15
    const val GATT_AUTH_FAIL = 137

    fun isAuthFailure(status: Int?): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_AUTH_FAIL

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
