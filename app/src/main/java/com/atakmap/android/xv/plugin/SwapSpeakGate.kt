package com.atakmap.android.xv.plugin

/**
 * The service can-speak gate around a channel swap.
 *
 * Both slots are moving while a swap (or its rollback) is in flight, so
 * both gates close BEFORE the swap starts: closing after the transport
 * call returned raced fast server acks (the outcome reopened the gate,
 * then the late close shut it again, leaving both slots denied). Any
 * other gate change during the swap (a suppress update, channel 2
 * coming live) is recorded by the caller but not pushed; the reopen
 * pushes the settled verdicts.
 */
class SwapSpeakGate(
    private val push: (slot: Int, canSpeak: Boolean) -> Unit,
) {
    @Volatile
    var closed: Boolean = false
        private set

    /** Close both slots; call before starting the swap. */
    @Synchronized
    fun close() {
        closed = true
        push(0, false)
        push(1, false)
    }

    /**
     * Reopen with the current verdicts: after the swap's outcome, or
     * when the transport refused to start it.
     */
    @Synchronized
    fun reopen(
        slot0: Boolean,
        slot1: Boolean,
    ) {
        closed = false
        push(0, slot0)
        push(1, slot1)
    }

    /** Push a single slot's verdict unless a swap holds the gate shut. */
    @Synchronized
    fun pushIfOpen(
        slot: Int,
        canSpeak: Boolean,
    ): Boolean {
        if (closed) return false
        push(slot, canSpeak)
        return true
    }
}
