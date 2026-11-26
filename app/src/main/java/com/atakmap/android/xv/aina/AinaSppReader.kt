package com.atakmap.android.xv.aina

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.atakmap.android.xv.transport.ReconnectPolicy
import java.io.IOException
import java.util.UUID

// AINA APTT V1 — Bluetooth Classic SPP/RFCOMM. The V1 hardware emits ASCII
// frames (`+PTT=P`, `+PTT=R`, etc) on a serial channel discovered via the
// standard SPP UUID. Read loop owns the socket and blocks on
// inputStream.read(); callers must marshal back to whatever thread they need.
@SuppressLint("MissingPermission")
class AinaSppReader(
    private val context: Context,
    private val onEvent: (AinaButton, isDown: Boolean) -> Unit,
    private val onConnectionState: (Boolean) -> Unit = {},
    // Fired exactly once when the SPP reader hits a non-recoverable
    // condition (today: BLUETOOTH_CONNECT runtime permission revoked).
    // The reader does NOT enter the ReconnectPolicy backoff after a
    // fatal — the operator has to act (re-grant the permission, or
    // pair a different device) before another connect attempt makes
    // sense. Callers can surface this in the UI; default no-op keeps
    // older call sites compiling unchanged.
    private val onFatal: (reason: String) -> Unit = {},
    // Test seam — injected socket factory. Production stays on the
    // BluetoothDevice methods; unit tests pass a fake factory that
    // can throw SecurityException / IOException on demand so the
    // permission-revoked and SDP-failed branches can be exercised
    // without standing up a real BluetoothSocket. Public so unit
    // tests in the same module can substitute without needing
    // reflection (Kotlin's overload-resolution rules block a SAM
    // lambda when the type itself is internal).
    private val socketFactory: SocketFactory = DefaultSocketFactory,
) {
    // Live, connected socket of the CURRENT generation (null until
    // connect() succeeds and again once the read loop exits).
    @Volatile
    private var socket: BluetoothSocket? = null

    // Socket of the current generation that is still inside its
    // blocking connect(). Tracked separately so disconnect() can close
    // it — closing is the only way to abort BluetoothSocket.connect(),
    // and before this field existed a disconnect() during the connect
    // left the attempt running: it would later succeed, hold the
    // RFCOMM link, and fire connected/disconnected edges into a
    // replacement reader's state.
    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    // Bumped by every connect() and disconnect(). Each read-loop thread
    // captures the value it was started with; once it no longer
    // matches, that thread is stale and must not touch shared state,
    // fire callbacks, or schedule reconnects.
    @Volatile
    private var generation: Int = 0

    private val lifecycleLock = Any()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var stopRequested: Boolean = false

    // Last device the operator selected. Survives socket churn so the
    // reconnect path knows who to re-attach to. Cleared by disconnect().
    @Volatile
    private var targetDevice: BluetoothDevice? = null

    // Backoff state for the reconnect path. Resets on every successful
    // socket open. Tuned for the BR/EDR range scenario — operator
    // walks back into range within a minute or they don't.
    private val reconnectPolicy = ReconnectPolicy(ReconnectPolicy.SPP_SCHEDULE)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable =
        Runnable {
            synchronized(lifecycleLock) {
                val dev = targetDevice
                if (stopRequested || dev == null) return@Runnable
                // Only a thread of the current generation schedules this,
                // and it has already left its pump loop when it does, so
                // starting a fresh thread on the same generation is safe.
                Log.i(TAG, "reconnect: re-opening SPP to ${redactMac(dev.address)}")
                startReadThread(dev, generation)
            }
        }

    fun connect(device: BluetoothDevice) {
        disconnect()
        synchronized(lifecycleLock) {
            stopRequested = false
            targetDevice = device
            reconnectPolicy.reset()
            val gen = ++generation
            Log.i(
                TAG,
                "Connecting SPP to AINA at ${redactMac(device.address)} (${device.name ?: "?"}) gen=$gen",
            )
            startReadThread(device, gen)
        }
    }

    private fun startReadThread(
        device: BluetoothDevice,
        gen: Int,
    ) {
        thread = Thread({ runReadLoop(device, gen) }, "aina-spp-reader").also { it.start() }
    }

    fun isConnecting(): Boolean = thread?.isAlive == true

    /** True when an RFCOMM socket to [mac] is open and pumping. Lets
     *  the respawn path skip tearing down a working link (ACL_CONNECTED,
     *  STATE_ON and config-change triggers all re-run connect). */
    fun isConnectedTo(mac: String?): Boolean =
        !mac.isNullOrBlank() &&
            !stopRequested &&
            socket != null &&
            targetDevice?.address.equals(mac, ignoreCase = true)

    fun disconnect() {
        val toClose: List<BluetoothSocket?>
        synchronized(lifecycleLock) {
            stopRequested = true
            generation++
            targetDevice = null
            reconnectHandler.removeCallbacks(reconnectRunnable)
            reconnectPolicy.reset()
            toClose = listOf(pendingSocket, socket)
            pendingSocket = null
            socket = null
            thread?.interrupt()
            thread = null
        }
        // Close outside the lock: BluetoothSocket.close() can block
        // briefly, and closing the pending socket is what unblocks a
        // read thread parked in connect().
        toClose.forEach { closeQuietly(it) }
        notifyConnection(false)
    }

    private fun isCurrent(gen: Int): Boolean = !stopRequested && gen == generation

    private fun closeQuietly(sock: BluetoothSocket?) {
        try {
            sock?.close()
        } catch (_: Throwable) {
        }
    }

    // Callbacks run on the reader thread. A throw from the plugin side
    // must neither kill the thread (leaking the socket) nor escape to
    // the default uncaught-exception handler (crashing ATAK).
    private fun notifyConnection(up: Boolean) {
        try {
            onConnectionState(up)
        } catch (t: Throwable) {
            Log.w(TAG, "onConnectionState($up) handler threw", t)
        }
    }

    // Schedule the next reconnect attempt using the shared
    // ReconnectPolicy. Caller fires this when the read loop exits
    // (out of range, socket dropped) OR when openSppSocketWithRetry
    // gives up its in-burst attempts. The policy decides delay; we
    // post on the main looper so it doesn't pile up on the read
    // thread that's about to exit.
    private fun scheduleReconnect(
        gen: Int,
        reason: String,
    ) {
        if (!isCurrent(gen)) return
        if (targetDevice == null) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val delay = reconnectPolicy.nextDelayMs()
        Log.i(
            TAG,
            "scheduleReconnect: attempt ${reconnectPolicy.attemptCount()} in ${delay}ms ($reason)",
        )
        reconnectHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun runReadLoop(
        device: BluetoothDevice,
        gen: Int,
    ) {
        try {
            runReadLoopInner(device, gen)
        } catch (t: Throwable) {
            // Anything unexpected (RuntimeException from the BT stack,
            // a bug in the parser) lands here instead of the thread's
            // uncaught-exception handler, which would take ATAK down.
            Log.e(TAG, "SPP reader thread failed unexpectedly (gen=$gen)", t)
            if (isCurrent(gen)) {
                notifyConnection(false)
                scheduleReconnect(gen, "reader thread exception")
            }
        }
    }

    private fun runReadLoopInner(
        device: BluetoothDevice,
        gen: Int,
    ) {
        // BluetoothSocket contract: cancel discovery before connect or it may block.
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Throwable) {
        }

        // V1 voice responders frequently fail their first SPP connect
        // because the RFCOMM service record isn't advertised until the
        // ACL link is fully up. The jeffypooo/aina-android reference
        // retries up to 3 times — we do the same plus a small backoff
        // and an insecure-socket fallback on the final attempt for
        // devices whose service discovery is unreliable on Android 14+.
        when (val result = openSppSocketWithRetry(device, gen)) {
            is OpenResult.Success -> onSocketOpened(result.socket, gen)
            is OpenResult.Fatal -> {
                Log.e(TAG, "SPP open FATAL — not scheduling retry: ${result.reason}")
                if (isCurrent(gen)) {
                    notifyConnection(false)
                    try {
                        onFatal(result.reason)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onFatal handler threw", t)
                    }
                }
            }
            is OpenResult.Exhausted -> {
                if (isCurrent(gen)) {
                    notifyConnection(false)
                    // Initial connect burst exhausted. Stay in the
                    // reconnect loop so the operator doesn't have to dig
                    // into Settings when the AINA finally finishes
                    // booting / comes into range.
                    scheduleReconnect(gen, "initial connect burst exhausted")
                }
            }
            is OpenResult.Cancelled -> Log.i(TAG, "SPP connect cancelled by disconnect (gen=$gen)")
        }
    }

    private fun onSocketOpened(
        sock: BluetoothSocket,
        gen: Int,
    ) {
        // Publish under the lock so a racing disconnect() either sees
        // this socket (and closes it) or has already bumped the
        // generation (and we close it here).
        val published =
            synchronized(lifecycleLock) {
                if (isCurrent(gen)) socket = sock
                isCurrent(gen)
            }
        if (!published) {
            Log.i(TAG, "SPP connect finished after disconnect (gen=$gen) — closing stale socket")
            closeQuietly(sock)
        } else {
            Log.i(TAG, "SPP connected (gen=$gen)")
            // Successful open — reset backoff so the next drop starts
            // from the short end of the schedule again.
            reconnectPolicy.reset()
            // pumpInputStream owns the socket from here and closes it in
            // its finally block; notifyConnection never throws.
            notifyConnection(true)
            pumpInputStream(sock, gen)
        }
    }

    private fun pumpInputStream(
        sock: BluetoothSocket,
        gen: Int,
    ) {
        val buf = ByteArray(64)
        // Parser per read loop, not per reader: a stale thread still
        // draining must not interleave bytes into the live one's state.
        val parser = AinaAsciiParser()
        try {
            val input = sock.inputStream
            while (isCurrent(gen)) {
                val n =
                    try {
                        input.read(buf)
                    } catch (t: IOException) {
                        if (isCurrent(gen)) Log.w(TAG, "SPP read error: ${t.message}")
                        -1
                    }
                if (n <= 0 || !isCurrent(gen)) break
                Log.i(
                    TAG,
                    "rx ${n}B: ascii='${printable(buf, n)}' hex=${hex(buf, n)}",
                )
                dispatchFrames(parser.process(buf, n), gen)
            }
        } catch (t: IOException) {
            if (isCurrent(gen)) Log.w(TAG, "SPP input stream unavailable: ${t.message}")
        } finally {
            Log.i(TAG, "SPP read loop exiting (gen=$gen)")
            closeQuietly(sock)
            val current =
                synchronized(lifecycleLock) {
                    if (socket === sock) socket = null
                    isCurrent(gen)
                }
            // A stale generation already had its disconnect edge
            // delivered by disconnect(); firing another here would
            // release a burst held on the replacement reader.
            if (current) {
                notifyConnection(false)
                // Read-loop exit while still current means out-of-range
                // / socket dropped: reconnect so the operator's PTT
                // works again when they walk back into range.
                scheduleReconnect(gen, "read loop exit")
            }
        }
    }

    private fun dispatchFrames(
        events: List<AinaAsciiParser.ButtonEdge>,
        gen: Int,
    ) {
        for ((button, isDown) in events) {
            Log.i(TAG, "parsed: $button down=$isDown")
            // A disconnect() may land between frames; never hand a
            // superseded reader's edges to the plugin.
            if (!isCurrent(gen)) break
            try {
                onEvent(button, isDown)
            } catch (t: Throwable) {
                Log.w(TAG, "onEvent handler threw", t)
            }
        }
    }

    internal sealed class OpenResult {
        data class Success(
            val socket: BluetoothSocket,
        ) : OpenResult()

        // Hard failure — caller MUST NOT schedule another retry.
        // Today only one trigger: BLUETOOTH_CONNECT runtime permission
        // revoked (SecurityException survives the secure→insecure
        // fallback). Carries a human-readable reason so the UI layer
        // can render *why* without parsing logs.
        data class Fatal(
            val reason: String,
        ) : OpenResult()

        // Burst exhausted but still recoverable — schedule the next
        // ReconnectPolicy delay. This is the "AINA hasn't finished
        // booting / is out of range" case.
        object Exhausted : OpenResult()

        // disconnect() (or a newer connect()) superseded this attempt.
        // No callbacks, no retry — the newer generation owns the slot.
        object Cancelled : OpenResult()
    }

    // Mutable state carried across the attempts of one connect burst.
    private class Burst {
        var lastErr: Throwable? = null

        // Initially follow the legacy attempt-3-uses-insecure schedule.
        // Flipped early to true the moment we see an "SDP failed"
        // IOException on createRfcomm... — that signal tells us the
        // secure path will never work this burst (M4 quick-win), so we
        // save two attempts' worth of latency by jumping straight to
        // the insecure fallback.
        var forceInsecure = false

        // Set when socket creation proves BLUETOOTH_CONNECT is revoked.
        var fatal: OpenResult.Fatal? = null
    }

    private fun openSppSocketWithRetry(
        device: BluetoothDevice,
        gen: Int,
    ): OpenResult {
        val burst = Burst()
        var result: OpenResult? = null
        var attempt = 0
        while (result == null && attempt < MAX_CONNECT_ATTEMPTS) {
            attempt++
            result = if (isCurrent(gen)) attemptOnce(device, gen, attempt, burst) else OpenResult.Cancelled
        }
        if (result == null) {
            Log.e(TAG, "SPP connect failed after $MAX_CONNECT_ATTEMPTS attempts", burst.lastErr)
        }
        return result ?: OpenResult.Exhausted
    }

    // One create + connect attempt. Returns a terminal result, or null
    // to retry (after the backoff sleep).
    private fun attemptOnce(
        device: BluetoothDevice,
        gen: Int,
        attempt: Int,
        burst: Burst,
    ): OpenResult? {
        // First N-1 attempts use the standard SDP-based secure
        // socket. The final attempt falls back to an insecure
        // socket (no SDP lookup, fixed channel 1) because some
        // V1 firmware revisions advertise SDP unreliably.
        val useInsecure = burst.forceInsecure || attempt == MAX_CONNECT_ATTEMPTS
        Log.i(
            TAG,
            "SPP connect attempt $attempt bond=${bondStateLabel(device)} insecure=$useInsecure " +
                "mac=${redactMac(device.address)}",
        )
        val sock = createSocket(device, attempt, useInsecure, burst)
        val result =
            when {
                burst.fatal != null -> burst.fatal
                sock == null -> null
                else -> connectSocket(sock, gen, attempt, useInsecure, burst)
            }
        if (result == null) sleepBackoff(attempt)
        return result
    }

    // Log bond state at each connect attempt — distinguishing "AINA
    // wasn't pre-bonded" from "AINA bonded but service not advertising
    // yet" is one of the field-recurring diagnoses we want quick
    // visibility on.
    private fun bondStateLabel(device: BluetoothDevice): String =
        try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "BONDED"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_NONE -> "NONE"
                else -> "UNKNOWN(${device.bondState})"
            }
        } catch (_: Throwable) {
            "UNREADABLE"
        }

    private fun createSocket(
        device: BluetoothDevice,
        attempt: Int,
        useInsecure: Boolean,
        burst: Burst,
    ): BluetoothSocket? {
        val kind = "create${if (useInsecure) "Insecure" else ""}Rfcomm..."
        return try {
            socketFactory.create(device, SPP_UUID, useInsecure)
        } catch (se: SecurityException) {
            // Quick-win M3: SecurityException on RFCOMM socket create on
            // Android 12+ means BLUETOOTH_CONNECT is revoked. Retrying
            // produces the same outcome forever; the operator has to
            // re-grant the permission before another attempt makes
            // sense. Log loudly, propagate via the fatal callback, and
            // DO NOT enter the ReconnectPolicy backoff.
            Log.e(
                TAG,
                "$kind attempt $attempt threw SecurityException — " +
                    "perm=${permissionStatus()} mac=${redactMac(device.address)} — " +
                    "BLUETOOTH_CONNECT revoked, treating as FATAL",
                se,
            )
            burst.fatal =
                OpenResult.Fatal(
                    "BLUETOOTH_CONNECT runtime permission revoked " +
                        "(SecurityException on RFCOMM create)",
                )
            null
        } catch (ioe: IOException) {
            // Quick-win M4: "SDP failed" / "service not found" on
            // createRfcomm... means SDP lookup hit a busted record on the
            // AINA. The secure path can't recover within the same burst
            // because SDP doesn't get re-fetched per attempt — flip to
            // insecure (fixed channel 1, no SDP lookup) immediately for
            // the rest of the burst so a device with a permanently
            // busted SDP record gets the insecure path inside the FIRST
            // burst instead of waiting for a full reconnect cycle.
            Log.w(TAG, "$kind attempt $attempt threw IOException: ${ioe.message}", ioe)
            burst.lastErr = ioe
            if (!useInsecure && isSdpFailureMessage(ioe.message)) {
                Log.w(TAG, "SDP-style failure detected — switching to INSECURE for remainder of burst")
                burst.forceInsecure = true
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "$kind attempt $attempt failed", t)
            burst.lastErr = t
            null
        }
    }

    // Expose [sock] to disconnect() as the pending socket while
    // connect() blocks. Returns false (and closes it) if a disconnect
    // already superseded this generation.
    private fun publishPending(
        sock: BluetoothSocket,
        gen: Int,
    ): Boolean {
        val published =
            synchronized(lifecycleLock) {
                if (isCurrent(gen)) pendingSocket = sock
                isCurrent(gen)
            }
        if (!published) closeQuietly(sock)
        return published
    }

    private fun clearPending(sock: BluetoothSocket) {
        synchronized(lifecycleLock) {
            if (pendingSocket === sock) pendingSocket = null
        }
    }

    // Returns a terminal result, or null to retry.
    private fun connectSocket(
        sock: BluetoothSocket,
        gen: Int,
        attempt: Int,
        useInsecure: Boolean,
        burst: Burst,
    ): OpenResult? {
        if (!publishPending(sock, gen)) return OpenResult.Cancelled
        val result: OpenResult? =
            try {
                sock.connect()
                clearPending(sock)
                // disconnect() may have run while connect() was in
                // flight on a stack that ignored the close. Recheck.
                if (isCurrent(gen)) {
                    logConnected(attempt, useInsecure)
                    OpenResult.Success(sock)
                } else {
                    closeQuietly(sock)
                    OpenResult.Cancelled
                }
            } catch (se: SecurityException) {
                // SecurityException from .connect() (not the create) is a
                // strong signal that BLUETOOTH_CONNECT is gone — the
                // socket builder accepted but the actual connect can't
                // get past the runtime permission gate. Fatal.
                Log.e(
                    TAG,
                    "SPP connect attempt $attempt threw SecurityException — perm=${permissionStatus()}",
                    se,
                )
                clearPending(sock)
                closeQuietly(sock)
                OpenResult.Fatal(
                    "BLUETOOTH_CONNECT runtime permission revoked " +
                        "(SecurityException on RFCOMM connect)",
                )
            } catch (t: IOException) {
                clearPending(sock)
                closeQuietly(sock)
                // IOException is also how a disconnect()-initiated close
                // surfaces; don't log it as a failure or back off.
                if (isCurrent(gen)) {
                    Log.w(TAG, "SPP connect attempt $attempt failed: ${t.message}")
                    burst.lastErr = t
                    null
                } else {
                    OpenResult.Cancelled
                }
            }
        return result
    }

    private fun logConnected(
        attempt: Int,
        useInsecure: Boolean,
    ) {
        if (useInsecure) {
            // Insecure-socket fallback succeeded. The connection skipped
            // SDP lookup and is using fixed channel 1 without
            // authenticated pairing of the RFCOMM service. For AINA V1
            // speakermics this is harmless — the device only carries
            // button events, no PII — but it's worth flagging in case a
            // future device class surfaces here where the insecure path
            // matters. Field complaint M9: this fallback was previously
            // silent on success; only the failed attempts logged.
            Log.w(
                TAG,
                "SPP connected via INSECURE fallback on attempt $attempt — " +
                    "device's RFCOMM SDP record is unreliable, using unauthenticated channel-1 path",
            )
        } else {
            Log.i(TAG, "SPP connect succeeded on attempt $attempt (secure)")
        }
    }

    private fun sleepBackoff(attempt: Int) {
        // 400ms, 800ms — enough for the AINA's RFCOMM service to settle
        // post-ACL but short enough not to feel laggy if it's just busy.
        val sleepMs = 400L * attempt
        try {
            Thread.sleep(sleepMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun permissionStatus(): String =
        try {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) "BLUETOOTH_CONNECT=GRANTED" else "BLUETOOTH_CONNECT=DENIED"
        } catch (_: Throwable) {
            "BLUETOOTH_CONNECT=UNREADABLE"
        }

    private fun isSdpFailureMessage(msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        val lower = msg.lowercase()
        return lower.contains("sdp") || lower.contains("service") || lower.contains("read failed")
    }

    private fun printable(
        buf: ByteArray,
        n: Int,
    ): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = (buf[i].toInt() and 0xFF).toChar()
            sb.append(
                when {
                    c == '\r' -> "\\r"
                    c == '\n' -> "\\n"
                    c.code in 0x20..0x7E -> c.toString()
                    else -> "."
                },
            )
        }
        return sb.toString()
    }

    private fun hex(
        buf: ByteArray,
        n: Int,
    ): String {
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", buf[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Test seam — abstracts the two BluetoothDevice socket-builder
     * methods so unit tests can supply a fake. Production uses
     * [DefaultSocketFactory] which just delegates to the device.
     *
     * Public so unit tests in the same module can pass a SAM lambda
     * via the [AinaSppReader] primary constructor (Kotlin overload
     * resolution rejects the lambda when the SAM type is internal).
     * Production callers don't reference this type at all.
     */
    fun interface SocketFactory {
        @Throws(IOException::class, SecurityException::class)
        fun create(
            device: BluetoothDevice,
            uuid: UUID,
            insecure: Boolean,
        ): BluetoothSocket
    }

    object DefaultSocketFactory : SocketFactory {
        override fun create(
            device: BluetoothDevice,
            uuid: UUID,
            insecure: Boolean,
        ): BluetoothSocket =
            if (insecure) {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } else {
                device.createRfcommSocketToServiceRecord(uuid)
            }
    }

    companion object {
        private const val TAG = "AinaSppReader"
        private const val MAX_CONNECT_ATTEMPTS = 3

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Respawn guard: true when a connect request should leave the
         * existing SPP reader alone because it is already pumping on the
         * requested device and the request still resolves to the SPP
         * (V1) protocol. A protocol change (e.g. to "v2") always respawns.
         */
        fun shouldKeepLiveReader(
            liveOnSameMac: Boolean,
            resolvedKind: String?,
        ): Boolean = liveOnSameMac && (resolvedKind == "v1" || resolvedKind == "spp")
    }
}
