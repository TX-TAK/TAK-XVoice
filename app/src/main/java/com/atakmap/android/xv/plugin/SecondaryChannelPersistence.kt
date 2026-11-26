package com.atakmap.android.xv.plugin

/**
 * Rules for what channel-2 (secondary slot) state is persisted and
 * restored. Pure so the policy is unit-testable without a MapView.
 */
object SecondaryChannelPersistence {
    /** Prefix of server-created temporary direct-call channels. */
    const val PRIVATE_CALL_PREFIX = "TAK PRIVATE - "

    /** Persisted raw value → channel 2 to restore on connect, or null = off. */
    fun restore(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        // A temp call channel is deleted server-side when the call ends;
        // never try to rejoin one (mirrors the channel-1 guard).
        return v.takeUnless { it.isEmpty() || it.startsWith(PRIVATE_CALL_PREFIX) }
    }

    /**
     * Whether a server-confirmed slot-1 move should overwrite the
     * persisted channel 2. Skips temp call channels and moves that are
     * one leg of an in-flight swap (the swap outcome persists instead,
     * so a rolled-back swap never leaves a half-swapped value behind).
     */
    fun shouldPersistMove(
        name: String?,
        swapInFlight: Boolean,
    ): Boolean = !swapInFlight && restore(name) != null

    /**
     * Channel 1 for a reconnect attempt. The first attempt honours the
     * configured channel (an explicit request: debug connect, comms-plan
     * import). Later attempts use the persisted channel 1, so a reconnect
     * after a picker move or a swap lands VS1 where the operator left it
     * (not on the channel VS2 now holds).
     */
    fun reconnectPrimary(
        firstAttempt: Boolean,
        configured: String,
        persisted: String?,
    ): String = if (firstAttempt) configured else restore(persisted) ?: configured

    /**
     * Whether a (channel 1, channel 2) pair read after a swap may be
     * persisted. A half-swap can leave both sessions on one channel; saving
     * that would start both slots on it at reconnect, where the VS1 != VS2
     * guard drops channel 2. Channel 2 off (null/blank) is always safe.
     */
    fun safeToPersistPair(
        primary: String?,
        secondary: String?,
    ): Boolean {
        val p = restore(primary)
        val s = restore(secondary)
        return p != null && (s == null || !com.atakmap.android.xv.transport.AutoJoinPolicy.sameChannel(p, s))
    }
}
