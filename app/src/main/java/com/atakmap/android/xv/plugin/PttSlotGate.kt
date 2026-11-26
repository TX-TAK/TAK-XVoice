package com.atakmap.android.xv.plugin

/**
 * The service-side "can this slot speak?" gate for the two PTT buttons.
 *
 * Operator rule: PTT1 keys channel 1, PTT2 keys channel 2, and with
 * channel 2 off BOTH key channel 1. So while channel 2 is off, slot 1's
 * gate must be channel 1's gate — otherwise a stale listen-only verdict
 * from a channel 2 the operator already turned off keeps PTT2 bonking,
 * or a listen-only channel 1 lets PTT2 bypass channel 1's own bonk.
 */
object PttSlotGate {
    /**
     * @param channel2On whether channel 2 is configured.
     * @param channel2Live whether channel 2's session is joined and keyable.
     * @param slot0Suppressed server suppress (listen-only) on channel 1.
     * @param slot1Suppressed server suppress on channel 2 (ignored when off).
     *
     * Channel 2 configured but not live (joining / reconnecting / down for
     * a call) is a refusal: PTT2 must NOT silently key channel 1 then.
     */
    fun canSpeakSlot1(
        channel2On: Boolean,
        channel2Live: Boolean,
        slot0Suppressed: Boolean,
        slot1Suppressed: Boolean,
    ): Boolean = if (channel2On) channel2Live && !slot1Suppressed else !slot0Suppressed
}
