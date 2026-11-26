package com.atakmap.android.xv.service

/**
 * Operator rule: during any phone call, channel RX is ignored — not
 * played and not buffered. (Replaying what was missed is a separate
 * backlog item; nothing here keeps audio.)
 *
 * "Phone call" is [ExternalCallDetector]'s verdict (cellular, ringing,
 * or another app's VoIP call), the same signal the PTT gate and the
 * panel use.
 *
 * During an XV direct call, the call itself rides channel 1's session
 * (moved to the call's temporary channel), so only channel 2's RX is
 * dropped.
 */
object CallRxPolicy {
    fun dropChannelRx(
        slot: Int,
        phoneCall: Boolean,
        xvDirectCall: Boolean,
    ): Boolean = phoneCall || (xvDirectCall && slot != 0)
}
