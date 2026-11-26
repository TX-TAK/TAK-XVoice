package com.atakmap.android.xv.transport

/**
 * Which session a TX frame for a requested slot goes out on.
 *
 * Operator rule: PTT2 keys channel 1 ONLY when channel 2 is off. While
 * channel 2 is configured but not live (joining, reconnecting, torn down
 * for a call), a PTT2 frame must never leave on channel 1 — it is dropped
 * (the PTT gate already refuses with the deny tone; this is the transport
 * backstop). The one shared-channel case is mid-swap, where both sessions
 * briefly sit on one channel and the primary carries it.
 */
object SlotRouting {
    enum class Route { PRIMARY, SECONDARY, DROP }

    fun route(
        requestedSlot: Int,
        secondaryConfigured: Boolean,
        secondaryPresent: Boolean,
        secondaryJoined: Boolean,
        sameChannelAsPrimary: Boolean,
    ): Route =
        when {
            requestedSlot <= 0 -> Route.PRIMARY
            !secondaryConfigured && !secondaryPresent -> Route.PRIMARY // channel 2 off
            !secondaryPresent || !secondaryJoined -> Route.DROP // channel 2 configured, not live
            sameChannelAsPrimary -> Route.PRIMARY // mid-swap overlap
            else -> Route.SECONDARY
        }
}
