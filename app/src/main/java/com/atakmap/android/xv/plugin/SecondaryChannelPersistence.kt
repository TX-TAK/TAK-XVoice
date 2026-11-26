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
}
