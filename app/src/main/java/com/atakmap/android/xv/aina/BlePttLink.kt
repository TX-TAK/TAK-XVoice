package com.atakmap.android.xv.aina

/**
 * What VoicePlant needs to know about a live BLE PTT reader before
 * replacing it. Background reconnect triggers (ACL_CONNECTED, adapter
 * STATE_ON) re-run the connect path; replacing a reader that is already
 * connected cuts a live burst, and replacing one that is still connecting
 * or backing off aborts its setup and resets its backoff (see
 * [ReaderReuse]).
 */
interface BlePttLink {
    /** Button subscription confirmed for the device the owner asked for. */
    fun isConnectedTo(mac: String): Boolean

    /** Still connecting to, scanning for, or backing off on that device. */
    fun isWorkingOn(mac: String): Boolean
}

/**
 * Whether a connect request for a slot keeps the reader it already has.
 * Pure; VoicePlant feeds it the current reader's state.
 */
object ReaderReuse {
    enum class Action {
        /** Connected on the same device and protocol: keep it, re-report connected. */
        KEEP_CONNECTED,

        /** Same device and protocol, still connecting or backing off: leave it be. */
        KEEP_IN_PROGRESS,

        /** Different device or protocol, or the reader is idle: replace it. */
        REPLACE,
    }

    /**
     * @param sameKind the slot's reader runs the protocol the request
     *   resolves to (a protocol change always replaces it).
     * @param connected that reader is connected to the requested device.
     * @param inProgress that reader is still connecting to, scanning for
     *   or backing off on the requested device.
     */
    fun decide(
        sameKind: Boolean,
        connected: Boolean,
        inProgress: Boolean,
    ): Action =
        when {
            !sameKind -> Action.REPLACE
            connected -> Action.KEEP_CONNECTED
            inProgress -> Action.KEEP_IN_PROGRESS
            else -> Action.REPLACE
        }
}
